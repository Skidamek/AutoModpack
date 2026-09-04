package pl.skidam.automodpack_core.modpack.generation;

import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_JOURNAL_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_PROJECTION_FILE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.ServerObjectStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The server-side modpack state: a content-addressed object store plus an append-only journal of
 * content changes. The journal is the only truth; the projection document is a rebuilt view.
 */
public final class GenerationStore {

	private final Path root;
	private final Path journalFile;
	private final Path projectionFile;
	private final ServerObjectStore objectStore;
	private final Path objectsDirectory;

	private Journal journal;
	private Current current;

	public GenerationStore(Path root, Path objectsDirectory) {
		this.root = root.toAbsolutePath().normalize();
		this.journalFile = this.root.resolve(SERVER_JOURNAL_FILE.getFileName().toString());
		this.projectionFile = this.root.resolve(SERVER_PROJECTION_FILE.getFileName().toString());
		this.objectsDirectory = objectsDirectory.toAbsolutePath().normalize();
		this.objectStore = new ServerObjectStore(this.objectsDirectory, this.root.resolve("staging"));
	}

	public Path objectRoot() {
		return objectsDirectory;
	}

	Path journalFile() {
		return journalFile;
	}

	/** The current generation: the journal head's content with its policy document and ledger. */
	public Optional<Current> loadCurrent() throws IOException {
		if (current != null) return Optional.of(current);
		journal = Journal.open(journalFile);
		if (journal.isEmpty()) return Optional.empty();
		current = loadFromProjection();
		if (current == null) {
			JournalEntry head = journal.head();
			current = new Current(head.seq(), head.contentToken(), head.policySha1(), head.createdAt(), loadPolicy(head.policySha1()), replayLedger(head.seq()), journal.treeAt(head.seq()));
			writeProjection(current);
		}
		return Optional.of(current);
	}

	/**
	 * Rebuilds the current state from the projection view when it still matches the journal head:
	 * the view carries the folded ledger, so the common boot never replays the journal.
	 */
	private Current loadFromProjection() throws IOException {
		if (!Files.exists(projectionFile)) return null;
		GenerationJsons.HeadDocumentFields fields;
		try {
			fields = ConfigTools.read(projectionFile, GenerationJsons.HeadDocumentFields.class).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
		if (fields == null || fields.policy == null || fields.ownershipLedger == null) return null;
		JournalEntry head = journal.head();
		if (fields.journalHead != head.seq() || !fields.contentToken.equals(head.contentToken()) || !fields.policySha1.equals(head.policySha1())) return null;
		GroupManifest manifest;
		try {
			manifest = GroupManifestValidator.validate(fields.policy);
		} catch (RuntimeException e) {
			return null;
		}
		ContentTree tree = ContentTree.fromManifest(manifest);
		if (!tree.token().equals(head.contentToken())) return null;
		return new Current(head.seq(), head.contentToken(), head.policySha1(), head.createdAt(), manifest, OwnershipLedger.fromFields(fields.ownershipLedger), tree);
	}

	/** Publishes one candidate: promotes its objects, stores its policy document, and appends a journal entry. */
	public Publication publish(ModpackCandidate candidate, String notes) throws IOException {
		Current current = loadCurrent().orElse(null);
		GroupManifest manifest = candidate.manifest();
		ContentTree tree = ContentTree.fromManifest(manifest);
		String token = tree.token();

		byte[] policyBytes = ConfigTools.GSON.toJson(manifest.toFields()).getBytes(StandardCharsets.UTF_8);
		String policySha1 = HashUtils.sha1(policyBytes);
		writePolicyObject(policySha1, policyBytes);

		OwnershipLedger ledger = OwnershipLedger.materialize(current == null ? OwnershipLedger.empty(manifest.modpackId()) : current.ledger(), manifest);
		List<JournalEntry.Change> changes = diffTrees(current == null ? ContentTree.empty() : current.tree(), tree);

		objectStore.promoteAll(candidate.objects(), null);
		if (current != null && current.contentToken().equals(token)) {
			// Content is unchanged (a policy-only republish): refresh the head document without a journal entry.
			Current updated = new Current(current.seq(), token, policySha1, current.createdAt(), manifest, ledger, current.tree());
			this.current = updated;
			writeProjection(updated);
			return new Publication(journal.head(), manifest, ledger, hosting());
		}

		JournalEntry entry = new JournalEntry(current == null ? 1 : current.seq() + 1, token, policySha1, Instant.now(), notes, JournalEntry.NO_RESTORE, false, changes);
		journal.append(entry);
		Current updated = new Current(entry.seq(), token, policySha1, entry.createdAt(), manifest, ledger, tree);
		this.current = updated;
		writeProjection(updated);
		return new Publication(entry, manifest, ledger, hosting());
	}

	/** Restores the exact content and policy of a past journal entry as a new head entry. */
	public Publication publishRestore(long targetSeq, String notes) throws IOException {
		Current current = loadCurrent().orElseThrow(() -> new IOException("Nothing to restore before the root generation is published"));
		if (targetSeq == current.seq()) throw new IllegalArgumentException("Generation " + targetSeq + " is already the current generation");
		JournalEntry target = journal.entryAt(targetSeq);
		ContentTree targetTree = journal.treeAt(targetSeq);
		requireStoredObjects(targetTree);
		GroupManifest manifest = loadPolicy(target.policySha1());
		OwnershipLedger ledger = OwnershipLedger.materialize(current.ledger(), manifest);

		List<JournalEntry.Change> changes = diffTrees(current.tree(), targetTree);
		JournalEntry entry = new JournalEntry(current.seq() + 1, target.contentToken(), target.policySha1(), Instant.now(), notes, targetSeq, false, changes);
		journal.append(entry);

		Current updated = new Current(entry.seq(), target.contentToken(), target.policySha1(), entry.createdAt(), manifest, ledger, targetTree);
		this.current = updated;
		writeProjection(updated);
		return new Publication(entry, manifest, ledger, hosting());
	}

	/** Recent journal entries, oldest first, at most {@code limit} of them. */
	public List<JournalEntry> history(int limit) throws IOException {
		loadCurrent();
		List<JournalEntry> entries = journal.entries();
		int from = Math.max(0, entries.size() - Math.max(1, limit));
		return entries.subList(from, entries.size());
	}

	/**
	 * The hosting map: the head document under the reserved empty key, the journal file under the reserved journal
	 * key, and exactly the objects the head generation serves. Everything else stays on disk until an explicit collect.
	 */
	public GenerationHosting hosting() throws IOException {
		return hosting(loadCurrent().orElseThrow(() -> new IOException("No modpack generation is published")));
	}

	private GenerationHosting hosting(Current current) {
		Map<String, Path> paths = new TreeMap<>();
		paths.put(GenerationHosting.HEAD_DOCUMENT_KEY, projectionFile);
		paths.put(GenerationHosting.JOURNAL_KEY, journalFile);
		paths.put(current.policySha1(), DataRootResolver.objectFile(objectsDirectory, current.policySha1()));
		for (ContentTree.ContentFile file : current.tree().files().values()) paths.put(file.sha1(), DataRootResolver.objectFile(objectsDirectory, file.sha1()));
		return new GenerationHosting(paths);
	}

	/**
	 * Deletes content objects the current head generation no longer serves; collected objects make their generations
	 * unrestorable. Policy documents are never collected: they are the journal's metadata shadow, and the ledger
	 * replay plus any generation's manifest folding stay possible for the whole history.
	 */
	public CollectionSummary collectUnreachable() throws IOException {
		Current current = loadCurrent().orElse(null);
		TreeSet<String> reachable = new TreeSet<>();
		for (JournalEntry entry : journal.entries()) reachable.add(entry.policySha1());
		if (current != null) for (ContentTree.ContentFile file : current.tree().files().values()) reachable.add(file.sha1());
		long beforeBytes = 0;
		long beforeCount = 0;
		long deletedBytes = 0;
		long deletedCount = 0;
		List<Path> objects;
		try (var stream = Files.walk(objectsDirectory)) {
			objects = stream.filter(Files::isRegularFile).toList();
		}
		for (Path file : objects) {
			String sha1 = DataRootResolver.objectHash(objectsDirectory, file);
			if (sha1 == null) continue;
			beforeCount++;
			long size = Files.size(file);
			beforeBytes += size;
			if (!reachable.contains(sha1)) {
				Files.delete(file);
				deletedCount++;
				deletedBytes += size;
			}
		}
		return new CollectionSummary(beforeCount, beforeBytes, deletedCount, deletedBytes);
	}

	public record CollectionSummary(long objectsBefore, long bytesBefore, long deletedObjects, long deletedBytes) {}

	public StorageReport measureStorage() throws IOException {
		loadCurrent();
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

	private GroupManifest loadPolicy(String policySha1) throws IOException {
		Path object = DataRootResolver.objectFile(objectsDirectory, policySha1);
		FileTrees.requireRegularFile(object, "policy document");
		ModpackJsons.CompleteModpackContentFields fields = ConfigTools.parse(Files.readString(object, StandardCharsets.UTF_8), ModpackJsons.CompleteModpackContentFields.class);
		return GroupManifestValidator.validate(fields);
	}

	private void writePolicyObject(String policySha1, byte[] bytes) throws IOException {
		Path object = DataRootResolver.objectFile(objectsDirectory, policySha1);
		if (Files.exists(object)) return;
		Files.createDirectories(object.getParent());
		Path temporary = object.resolveSibling(object.getFileName() + ".tmp");
		Files.write(temporary, bytes);
		Files.move(temporary, object, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
		head.ownershipLedger = current.ledger().toFields();
		head.policy = current.manifest().toFields();
		ConfigTools.writeAtomic(projectionFile, head);
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
			else if (newFile == null) changes.add(JournalEntry.Change.removed(path, oldFile.sha1()));
			else
				if (!oldFile.sha1().equalsIgnoreCase(newFile.sha1()) || oldFile.size() != newFile.size())
					changes.add(new JournalEntry.Change(path, oldFile.sha1(), newFile.sha1(), newFile.size()));
		}
		return changes;
	}

	public record Current(long seq, String contentToken, String policySha1, Instant createdAt, GroupManifest manifest, OwnershipLedger ledger, ContentTree tree) {}

	public record Publication(JournalEntry entry, GroupManifest manifest, OwnershipLedger ledger, GenerationHosting hostingPaths) {}
}
