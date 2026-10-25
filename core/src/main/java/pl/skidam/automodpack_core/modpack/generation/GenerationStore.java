package pl.skidam.automodpack_core.modpack.generation;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_GENERATION_LOCK_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_JOURNAL_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_PROJECTION_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.WAITING_MUSIC_MAX_BYTES;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.ServerObjectStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance;
import pl.skidam.automodpack_core.storage.SharedObjectOwnership;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileLocks;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The server-side modpack state: a content-addressed object store plus an append-only journal of
 * content changes. The journal is the only truth; the projection document is a rebuilt view.
 */
public final class GenerationStore {
	private static final String OWNERSHIP_COMPONENT = "server";
	private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

	private final Path root;
	private final Path journalFile;
	private final Path projectionFile;
	private final Path lockFile;
	private final ServerObjectStore objectStore;
	private final Path objectsDirectory;
	private final Path waitingMusicSource;
	private final DataRootResolver.Location dataLocation;

	private Journal journal;
	private Current current;

	public GenerationStore(Path root, Path objectsDirectory) {
		this(root, objectsDirectory, new DataRootResolver.Location(objectsDirectory.getParent(), legacyOwnerId(objectsDirectory),
				objectsDirectory.getParent()), null);
	}

	public GenerationStore(Path root, Path objectsDirectory, DataRootResolver.Location dataLocation) {
		this(root, objectsDirectory, dataLocation, null);
	}

	/** {@code waitingMusicSource} is the convention track file; when present at publish it joins the object store and the head document. */
	public GenerationStore(Path root, Path objectsDirectory, Path waitingMusicSource) {
		this(root, objectsDirectory, new DataRootResolver.Location(objectsDirectory.getParent(), legacyOwnerId(objectsDirectory),
				objectsDirectory.getParent()), waitingMusicSource);
	}

	/** The production shape: {@code dataLocation} names this instance as the owner of the objects its generations pin. */
	public GenerationStore(Path root, Path objectsDirectory, DataRootResolver.Location dataLocation, Path waitingMusicSource) {
		this.root = root.toAbsolutePath().normalize();
		this.journalFile = this.root.resolve(SERVER_JOURNAL_FILE.getFileName().toString());
		this.projectionFile = this.root.resolve(SERVER_PROJECTION_FILE.getFileName().toString());
		this.lockFile = this.root.resolve(SERVER_GENERATION_LOCK_FILE.getFileName().toString());
		this.objectsDirectory = objectsDirectory.toAbsolutePath().normalize();
		this.waitingMusicSource = waitingMusicSource == null ? null : waitingMusicSource.toAbsolutePath().normalize();
		this.objectStore = new ServerObjectStore(this.objectsDirectory, this.root.resolve("staging"));
		this.dataLocation = Objects.requireNonNull(dataLocation, "data location");
	}

	public Path objectRoot() {
		return objectsDirectory;
	}

	Path journalFile() {
		return journalFile;
	}

	/** The current generation: the journal head's content with its policy document and ledger. */
	public Optional<Current> loadCurrent() throws IOException {
		return reading(this::peek);
	}

	/** {@link #loadCurrent} that also pins the head's objects, for a host that serves a generation it did not publish this run. */
	public Optional<Current> loadPinned() throws IOException {
		return locked(() -> {
			Optional<Current> head = head();
			if (head.isPresent()) publishReceipt(reachable(head.get()));
			return head;
		});
	}

	/**
	 * A read's cold load, which writes nothing: a torn journal tail is ignored and a stale projection is rebuilt in
	 * memory only. State that needs repair (unusable content, an unreadable projection) is loaded again under the
	 * store's lock, where the repair cannot cut another process's append.
	 */
	private Optional<Current> peek() throws IOException {
		if (current != null) return Optional.of(current);
		try {
			journal = Journal.openWithoutRepair(journalFile);
			if (journal.isEmpty()) return Optional.empty();
			Current projected = loadFromProjection(false);
			current = projected != null ? projected : rebuild(storedWaitingMusicObject());
			return Optional.of(current);
		} catch (IOException needsRepair) {
			return locked(this::head);
		}
	}

	/** The cold load of a locked write path, repairing a torn tail, archiving unusable state, and rewriting a stale projection. */
	private Optional<Current> head() throws IOException {
		if (current != null) return Optional.of(current);
		journal = openJournal();
		if (journal.isEmpty()) return Optional.empty();
		current = loadCurrentSetAsideOnFailure();
		return Optional.ofNullable(current);
	}

	/** Journal.open with the store-level heal: unusable *content* is archived aside and reopened empty. Physical IO of a readable journal propagates. */
	private Journal openJournal() throws IOException {
		try {
			return Journal.open(journalFile);
		} catch (Journal.UnusableContentException e) {
			archiveUnusableState(e);
			return Journal.open(journalFile);
		}
	}

	/**
	 * The current generation from the projection view, or rebuilt from the journal. Projection write trouble
	 * propagates: the journal stays the truth. Unusable journal content (replay that cannot be folded, a missing
	 * policy object after a torn publish) is archived aside so the next publish recreates the store from the server
	 * files. Unchanged content keeps its content token, so clients never re-download for that heal.
	 */
	private Current loadCurrentSetAsideOnFailure() throws IOException {
		Current projected = loadFromProjection(true);
		if (projected != null) return projected;
		try {
			Current rebuilt = rebuild(publishWaitingMusicObject(null));
			writeProjection(rebuilt);
			return rebuilt;
		} catch (Journal.UnusableContentException e) {
			archiveUnusableState(e);
			return null;
		}
	}

	private Current rebuild(String waitingMusicSha1) throws IOException {
		JournalEntry head = journal.head();
		return new Current(head.seq(), head.contentToken(), head.policySha1(), head.createdAt(), verifiedPolicy(head), replayLedger(head.seq()), journal.treeAt(head.seq()),
				waitingMusicSha1);
	}

	/** Archives generation state that cannot be loaded, preserving the evidence next to where it lived. */
	private void archiveUnusableState(Exception cause) throws IOException {
		LOGGER.error("The modpack generation state in {} is unusable ({}); it was archived aside and the next publish recreates it from the server files as a fresh generation."
				+ " Clients keep their content, only the generation history restarts.", root, cause, cause);
		DurableFiles.setAside(journalFile, "Server generation journal", cause);
		DurableFiles.setAside(projectionFile, "Server generation projection", cause);
		journal = Journal.open(journalFile);
	}

	/**
	 * Rebuilds the current state from the projection view when it still matches the journal head:
	 * the view carries the folded ledger, so the common boot never replays the journal. A stale
	 * view is the normal cache miss and reads as absent; unreadable content is set aside with a
	 * loud log before the rebuild below recreates the projection.
	 */
	private Current loadFromProjection(boolean repairing) throws IOException {
		Function<GenerationJsons.HeadDocumentFields, GenerationJsons.HeadDocumentFields> complete = document -> {
			if (document.policy == null || document.ownershipLedger == null) throw new IllegalArgumentException("Projection document is missing its policy or ownership ledger");
			return document;
		};
		GenerationJsons.HeadDocumentFields fields = (repairing
				? ConfigTools.readState(projectionFile, GenerationJsons.HeadDocumentFields.class, "Server generation projection", complete)
				: ConfigTools.readUnique(projectionFile, GenerationJsons.HeadDocumentFields.class, "Server generation projection", complete)).orElse(null);
		if (fields == null) return null;
		JournalEntry head = journal.head();
		if (fields.journalHead != head.seq() || !fields.contentToken.equals(head.contentToken()) || !fields.policySha1.equals(head.policySha1())) return null;
		GroupManifest manifest;
		try {
			manifest = GroupManifestValidator.validate(fields.policy);
		} catch (RuntimeException e) {
			LOGGER.warn("The projection's policy document is invalid; rebuilding the projection from the journal", e);
			return null;
		}
		ContentTree tree = ContentTree.fromManifest(manifest);
		if (!tree.token().equals(head.contentToken())) return null;
		return new Current(head.seq(), head.contentToken(), head.policySha1(), head.createdAt(), manifest, OwnershipLedger.fromFields(fields.ownershipLedger), tree,
				fields.waitingMusicSha1 == null ? "" : fields.waitingMusicSha1);
	}

	/**
	 * Publishes one candidate: promotes its objects, stores its policy document, and appends a journal entry. A
	 * metadata-only policy change lands as an empty-changes entry - the journal is the only truth, so the new policy
	 * must be reachable from it or the next boot rebuilds the old one. True no-change leaves the head untouched.
	 */
	public Publication publish(ModpackCandidate candidate, String notes, FileCache fileCache) throws IOException {
		return locked(() -> publishLocked(candidate, notes, fileCache));
	}

	private Publication publishLocked(ModpackCandidate candidate, String notes, FileCache fileCache) throws IOException {
		Current current = head().orElse(null);
		GroupManifest manifest = candidate.manifest();
		ContentTree tree = ContentTree.fromManifest(manifest);
		String token = tree.token();

		byte[] policyBytes = ConfigTools.GSON.toJson(manifest.toFields()).getBytes(StandardCharsets.UTF_8);
		String policySha1 = HashUtils.sha1(policyBytes);
		writePolicyObject(policySha1, policyBytes);

		OwnershipLedger ledger = OwnershipLedger.materialize(current == null ? OwnershipLedger.empty(manifest.modpackId()) : current.ledger(), manifest);
		List<JournalEntry.Change> changes = diffTrees(current == null ? ContentTree.empty() : current.tree(), tree);

		// Promotion runs before the no-change check: a corrupted object the fresh snapshot repaired
		// must be replaced even when the candidate ends up matching the current generation.
		TreeSet<String> pinned = reachable(current);
		pinned.add(policySha1);
		for (ContentTree.ContentFile file : tree.files().values()) pinned.add(file.sha1());
		if (waitingMusicSource != null && Files.isRegularFile(waitingMusicSource))
			pinned.add(HashUtils.normalizeSha1(fileCache == null ? HashUtils.getHash(waitingMusicSource) : fileCache.getOrComputeHash(waitingMusicSource)));
		publishReceipt(pinned);
		objectStore.promoteAll(candidate.objects(), fileCache);

		if (current != null && current.contentToken().equals(token) && current.policySha1().equals(policySha1)) {
			// The track's only head record is the projection, so a track-only republish still rewrites it; the journal
			// stays untouched, exactly as a no-change publish demands.
			if (waitingMusicSource != null) {
				Current withTrack = current.withWaitingMusic(publishWaitingMusicObject(fileCache));
				writeProjection(withTrack);
				this.current = withTrack;
				return new Publication(journal.head(), manifest, ledger, hosting(withTrack));
			}
			return new Publication(journal.head(), manifest, ledger, hosting(current));
		}

		JournalEntry entry = new JournalEntry(current == null ? 1 : current.seq() + 1, token, policySha1, Instant.now(), notes, JournalEntry.NO_RESTORE, changes);
		journal.append(entry);
		Current updated = new Current(entry.seq(), token, policySha1, entry.createdAt(), manifest, ledger, tree, publishWaitingMusicObject(fileCache));
		this.current = updated;
		writeProjection(updated);
		return new Publication(entry, manifest, ledger, hosting(updated));
	}

	/** Replaces notes on the live head journal entry. Content, policy, and seq stay; callers rebind hosting so the served journal size matches. */
	public JournalEntry replaceHeadNotes(String notes) throws IOException {
		return locked(() -> {
			if (head().isEmpty()) throw new IOException("No modpack generation is published");
			return journal.replaceHeadNotes(notes);
		});
	}

	/** Restores the exact content and policy of a past journal entry as a new head entry. */
	public Publication publishRestore(long targetSeq, String notes) throws IOException {
		return locked(() -> publishRestoreLocked(targetSeq, notes));
	}

	private Publication publishRestoreLocked(long targetSeq, String notes) throws IOException {
		Current current = head().orElseThrow(() -> new IOException("Nothing to restore before the root generation is published"));
		if (targetSeq == current.seq()) throw new IllegalArgumentException("Generation " + targetSeq + " is already the current generation");
		JournalEntry target = journal.entryAt(targetSeq);
		ContentTree targetTree = journal.treeAt(targetSeq);
		requireStoredObjects(targetTree);
		GroupManifest manifest = verifiedPolicy(target);
		OwnershipLedger ledger = OwnershipLedger.materialize(current.ledger(), manifest);

		List<JournalEntry.Change> changes = diffTrees(current.tree(), targetTree);
		JournalEntry entry = new JournalEntry(current.seq() + 1, target.contentToken(), target.policySha1(), Instant.now(), notes, targetSeq, changes);
		TreeSet<String> pinned = reachable(current);
		for (ContentTree.ContentFile file : targetTree.files().values()) pinned.add(file.sha1());
		publishReceipt(pinned);
		journal.append(entry);

		Current updated = new Current(entry.seq(), target.contentToken(), target.policySha1(), entry.createdAt(), manifest, ledger, targetTree, publishWaitingMusicObject(null));
		this.current = updated;
		writeProjection(updated);
		return new Publication(entry, manifest, ledger, hosting(updated));
	}

	/** Recent journal entries, oldest first, at most {@code limit} of them. */
	public List<JournalEntry> history(int limit) throws IOException {
		return reading(() -> {
			peek();
			List<JournalEntry> entries = journal.entries();
			int from = Math.max(0, entries.size() - Math.max(1, limit));
			return List.copyOf(entries.subList(from, entries.size()));
		});
	}

	/**
	 * The hosting map: the head document under the reserved head key, the journal file under the reserved journal
	 * key, exactly the objects the head generation serves, and the waiting track when the head advertises one that
	 * is still stored. Everything else stays on disk until an explicit collect.
	 */
	public GenerationHosting hosting() throws IOException {
		return reading(() -> hosting(peek().orElseThrow(() -> new IOException("No modpack generation is published"))));
	}

	private GenerationHosting hosting(Current current) {
		return GenerationHosting.of(projectionFile, journalFile, current.policySha1(), current.tree(), current.waitingMusicSha1(),
				sha1 -> DataRootResolver.objectFile(objectsDirectory, sha1));
	}

	/**
	 * Deletes content objects the current head generation no longer serves; collected objects make their generations
	 * unrestorable. Policy documents are never collected: they are the journal's metadata shadow, and the ledger
	 * replay plus any generation's manifest folding stay possible for the whole history. The pass publishes this
	 * store's ownership receipt and deletes only against every owner's pins on the shared data root, so instances
	 * reusing the same object store never collect each other's pinned bytes.
	 */
	public CollectionSummary collectUnreachable() throws IOException {
		return locked(this::collectUnreachableLocked);
	}

	private CollectionSummary collectUnreachableLocked() throws IOException {
		Current current = head().orElse(null);
		return SharedObjectOwnership.withGlobalReferences(dataLocation, OWNERSHIP_COMPONENT, reachable(current), globallyReferenced -> {
			List<Path> objects = ObjectStoreMaintenance.objectFiles(objectsDirectory);
			long beforeBytes = 0;
			for (Path file : objects) beforeBytes = ObjectStoreMaintenance.addExact(beforeBytes, Files.size(file), "object store bytes");
			ObjectStoreMaintenance.DeletionReceipt deletion = ObjectStoreMaintenance.deleteUnreachable(objectsDirectory, globallyReferenced);
			return new CollectionSummary(objects.size(), beforeBytes, deletion.deletedCount(), deletion.deletedBytes());
		});
	}

	/**
	 * Runs one change of this store's journal under the store's lock, so two generators on one instance (a world and the
	 * menu, or two processes) never append over each other. The cached head is dropped first: another writer may have moved it.
	 */
	private <T> T locked(FileLocks.LockedOperation<T> operation) throws IOException {
		Files.createDirectories(root);
		return reading(() -> FileLocks.withLock(lockFile, LinkOption.NOFOLLOW_LINKS, () -> {
			current = null;
			journal = null;
			return operation.run();
		}));
	}

	/** Runs a read under this JVM's lock for the store, so it never sees a write's dropped cache or pins a head mid-publish. */
	private <T> T reading(FileLocks.LockedOperation<T> operation) throws IOException {
		ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockKey(), ignored -> new ReentrantLock());
		jvmLock.lock();
		try {
			return operation.run();
		} finally {
			jvmLock.unlock();
		}
	}

	private Path lockKey() {
		return lockKey(root);
	}

	/** The real path a root has or will have: its nearest existing ancestor resolved through links, plus the segments not yet created. */
	static Path lockKey(Path root) {
		Path existing = root;
		while (existing != null && !Files.exists(existing)) existing = existing.getParent();
		if (existing == null) return root;
		try {
			return existing.toRealPath().resolve(existing.relativize(root)).normalize();
		} catch (IOException vanished) {
			return root;
		}
	}

	/** Every policy document in the journal plus the objects the head serves: what collection keeps and what the receipt pins. */
	private TreeSet<String> reachable(Current head) throws IOException {
		TreeSet<String> reachable = new TreeSet<>();
		if (journal != null) for (JournalEntry entry : journal.entries()) reachable.add(entry.policySha1());
		if (head != null) {
			for (ContentTree.ContentFile file : head.tree().files().values()) reachable.add(file.sha1());
			if (HashUtils.isSha1(head.waitingMusicSha1())) reachable.add(HashUtils.normalizeSha1(head.waitingMusicSha1()));
		}
		return reachable;
	}

	/**
	 * Pins this instance's objects on the data root before any state depends on them, so another owner's collect
	 * keeps them. The receipt older builds wrote under a store-derived owner is retired only where the data root is
	 * this instance's own; on a shared root it may be another instance's, so it stays and merely over-retains.
	 */
	private void publishReceipt(Set<String> pinned) throws IOException {
		SharedObjectOwnership.publish(dataLocation, OWNERSHIP_COMPONENT, pinned);
		String legacyOwner = legacyOwnerId(objectsDirectory);
		if (!legacyOwner.equals(dataLocation.ownerId()) && dataLocation.root().startsWith(dataLocation.ownerPath()))
			SharedObjectOwnership.retire(dataLocation, legacyOwner, OWNERSHIP_COMPONENT);
	}

	private static String legacyOwnerId(Path objectsDirectory) {
		return HashUtils.sha1("generation-store:" + objectsDirectory.toAbsolutePath().normalize());
	}

	public record CollectionSummary(long objectsBefore, long bytesBefore, long deletedObjects, long deletedBytes) {}

	public StorageReport measureStorage() throws IOException {
		return reading(this::measureStorageLocked);
	}

	private StorageReport measureStorageLocked() throws IOException {
		peek();
		long journalBytes = Files.exists(journalFile) ? Files.size(journalFile) : 0;
		long objectCount = 0;
		long objectBytes = 0;
		try (var stream = Files.walk(objectsDirectory)) {
			for (Path file : stream.filter(Files::isRegularFile).toList()) {
				objectCount++;
				objectBytes += Files.size(file);
			}
		}
		return new StorageReport(journal.length(), journalBytes, objectCount, objectBytes);
	}

	public record StorageReport(long journalEntries, long journalBytes, long objectCount, long objectBytes) {}

	/** Fails loudly before a restore commits when the target generation's bytes were already collected from disk. */
	private void requireStoredObjects(ContentTree tree) throws IOException {
		for (ContentTree.ContentFile file : tree.files().values()) {
			Path object = DataRootResolver.objectFile(objectsDirectory, file.sha1());
			if (!Files.isRegularFile(object))
				throw new IOException("Generation object " + file.sha1() + " is no longer stored; a collect removed it, so this generation cannot be restored");
		}
	}

	/**
	 * The policy of a generation this store is about to make current: its object's address is proven against its bytes and its tree against the journal's content token, so no divergent document can become hosting truth.
	 */
	private GroupManifest verifiedPolicy(JournalEntry entry) throws IOException {
		GroupManifest manifest = loadPolicy(entry.policySha1());
		if (!ContentTree.fromManifest(manifest).token().equals(entry.contentToken()))
			throw new Journal.UnusableContentException("Policy document of generation " + entry.seq() + " does not agree with the journal's content token: " + entry.policySha1());
		return manifest;
	}

	private GroupManifest loadPolicy(String policySha1) throws IOException {
		Path object = DataRootResolver.objectFile(objectsDirectory, policySha1);
		if (!Files.isRegularFile(object)) throw new Journal.UnusableContentException("Policy document is missing from the object store: " + policySha1);
		try {
			// The content address is the only verification: unlike every tree object, a policy document is read back
			// nowhere else, so bytes that no longer hash to their name would otherwise replay into hosting truth.
			byte[] bytes = Files.readAllBytes(object);
			if (!HashUtils.sha1(bytes).equalsIgnoreCase(policySha1)) throw new Journal.UnusableContentException("Policy document content does not match its address: " + policySha1);
			return GroupManifestValidator.validate(ConfigTools.parse(new String(bytes, StandardCharsets.UTF_8), ModpackJsons.CompleteModpackContentFields.class));
		} catch (RuntimeException e) {
			throw new Journal.UnusableContentException("Policy document is unusable: " + policySha1, e);
		}
	}

	private void writePolicyObject(String policySha1, byte[] bytes) throws IOException {
		Path object = DataRootResolver.objectFile(objectsDirectory, policySha1);
		if (Files.exists(object)) return;
		DurableFiles.writeAtomic(object, bytes);
	}

	/** Replays the journal from its root: every entry's policy document folds into the cumulative ownership ledger. */
	private OwnershipLedger replayLedger(long seq) throws IOException {
		OwnershipLedger ledger = null;
		for (JournalEntry entry : journal.entries()) {
			GroupManifest manifest = loadPolicy(entry.policySha1());
			ledger = OwnershipLedger.materialize(ledger == null ? OwnershipLedger.empty(manifest.modpackId()) : ledger, manifest);
			if (entry.seq() == seq) break;
		}
		return ledger;
	}

	private void writeProjection(Current current) throws IOException {
		GenerationJsons.HeadDocumentFields head = new GenerationJsons.HeadDocumentFields();
		head.contentToken = current.contentToken();
		head.policySha1 = current.policySha1();
		head.createdAt = current.createdAt().toString();
		head.journalHead = current.seq();
		head.waitingMusicSha1 = current.waitingMusicSha1();
		head.ownershipLedger = current.ledger().toFields();
		head.policy = current.manifest().toFields();
		ConfigTools.writeAtomic(projectionFile, head);
	}

	/** The convention track's object when it is already stored, without promoting anything: what a read may advertise. */
	private String storedWaitingMusicObject() throws IOException {
		if (waitingMusicSource == null || !Files.isRegularFile(waitingMusicSource) || Files.size(waitingMusicSource) > WAITING_MUSIC_MAX_BYTES) return "";
		String sha1 = HashUtils.normalizeSha1(HashUtils.getHash(waitingMusicSource));
		return FileIntegrity.matchesNamed(DataRootResolver.objectFile(objectsDirectory, sha1), Files.size(waitingMusicSource), sha1, null) ? sha1 : "";
	}

	/** Publishes the convention track through the object store's discipline when present; empty means this generation serves none, and a changed file lands at the next publish. */
	private String publishWaitingMusicObject(FileCache fileCache) throws IOException {
		if (waitingMusicSource == null || !Files.isRegularFile(waitingMusicSource)) return "";
		if (Files.size(waitingMusicSource) > WAITING_MUSIC_MAX_BYTES) {
			LOGGER.error("The waiting track {} exceeds {} bytes; it was not published. Shrink the file and publish again.",
					waitingMusicSource, WAITING_MUSIC_MAX_BYTES);
			return "";
		}
		String sha1 = HashUtils.normalizeSha1(fileCache == null ? HashUtils.getHash(waitingMusicSource) : fileCache.getOrComputeHash(waitingMusicSource));
		long size = Files.size(waitingMusicSource);
		if (!FileIntegrity.matchesNamed(DataRootResolver.objectFile(objectsDirectory, sha1), size, sha1, fileCache)) objectStore.promoteCopy(waitingMusicSource, sha1);
		return sha1;
	}

	private static List<JournalEntry.Change> diffTrees(ContentTree before, ContentTree after) {
		List<JournalEntry.Change> changes = new ArrayList<>();
		TreeSet<String> paths = new TreeSet<>();
		paths.addAll(before.files().keySet());
		paths.addAll(after.files().keySet());
		for (String path : paths) {
			ContentTree.ContentFile oldFile = before.files().get(path);
			ContentTree.ContentFile newFile = after.files().get(path);
			if (oldFile == null && newFile == null) continue;
			if (oldFile == null) changes.add(JournalEntry.Change.added(path, newFile.sha1(), newFile.size()));
			else if (newFile == null) changes.add(JournalEntry.Change.removed(path, oldFile.sha1(), oldFile.size()));
			else
				if (!oldFile.sha1().equalsIgnoreCase(newFile.sha1()) || oldFile.size() != newFile.size())
					changes.add(new JournalEntry.Change(path, oldFile.sha1(), oldFile.size(), newFile.sha1(), newFile.size()));
		}
		return changes;
	}

	public record Current(long seq, String contentToken, String policySha1, Instant createdAt, GroupManifest manifest, OwnershipLedger ledger, ContentTree tree,
			String waitingMusicSha1) {

		/** The same generation hosting a freshly published (or withdrawn) waiting track; the journal never records the track, so this never moves the head. */
		Current withWaitingMusic(String sha1) {
			return waitingMusicSha1.equals(sha1) ? this : new Current(seq, contentToken, policySha1, createdAt, manifest, ledger, tree, sha1);
		}
	}

	public record Publication(JournalEntry entry, GroupManifest manifest, OwnershipLedger ledger, GenerationHosting hostingPaths) {}
}
