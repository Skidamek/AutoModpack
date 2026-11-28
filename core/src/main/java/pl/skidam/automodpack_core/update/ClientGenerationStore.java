package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.candidate.PackOwnedFiles;
import pl.skidam.automodpack_core.modpack.generation.ContentTree;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance.ExpectedSizes;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Reconstructs pack generations offline: the journal mirror names generations, the client CAS holds witnessed
 * policies, and the exact ledger comes from the active-state pointer, the pending transaction, or the head mirror.
 */
public final class ClientGenerationStore {
	private static final Logger LOGGER = LogManager.getLogger(ClientGenerationStore.class);

	private final ClientStorage storage;

	public ClientGenerationStore(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage);
	}

	/** Every modpack id with a local journal mirror, consent or not: the mirror is cache a head fetch writes, never a record of an install. */
	List<String> mirroredPackIds() throws IOException {
		Path root = storage.historyDirectory();
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		FileTrees.requireDirectory(root, "client journal mirrors");
		try (Stream<Path> paths = Files.list(root)) {
			List<String> ids = new ArrayList<>();
			for (Path path : paths.sorted().toList()) {
				FileTrees.requireNoSymbolicLink(path, "client journal mirrors");
				if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client journal mirrors contain an unsupported entry: " + path);
				ids.add(ModpackId.requireValid(path.getFileName().toString()));
			}
			return List.copyOf(ids);
		}
	}

	/** Every installed pack: fetched from a server AND locally consented. A fetch alone is cache; only {@link #hasLocalState} is an install. */
	public List<String> installedPackIds() throws IOException {
		return installedPackIds(storage.readActiveState(), new ClientSelectionStore(storage.selectionFile()));
	}

	/** One shared-state read per discovery pass: the active pointer and the selection file are parsed for every pack otherwise. */
	public List<String> installedPackIds(ClientStorageJsons.ClientGenerationStateFields activeState, ClientSelectionStore selections) throws IOException {
		List<String> installed = new ArrayList<>();
		for (String modpackId : mirroredPackIds()) if (hasLocalState(modpackId, activeState, selections)) installed.add(modpackId);
		return List.copyOf(installed);
	}

	/**
	 * Whether this pack ever committed locally: it holds the active pointer or a stored group selection. This is the one
	 * consent predicate: the mirror cannot answer it, because the mirror is already fetched when the first install's
	 * review starts. Deactivation keeps the selection, so a deactivated pack is still not a first install; removal forgets both.
	 */
	public boolean hasLocalState(String modpackId) throws IOException {
		return hasLocalState(modpackId, storage.readActiveState(), new ClientSelectionStore(storage.selectionFile()));
	}

	public boolean hasLocalState(String modpackId, ClientStorageJsons.ClientGenerationStateFields activeState, ClientSelectionStore selections) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		if (activeState != null && normalizedModpackId.equals(activeState.modpackId)) return true;
		return selections.get(normalizedModpackId).isPresent();
	}

	/**
	 * What a generation on this instance must never publish as the player's own. Only an update not yet committed
	 * manages paths here whatever their bytes: every game-directory path its generation ships and its plan holds.
	 * Everything else is owned only at the exact bytes a pack held, from every snapshot's pack-held rows and every
	 * installed pack's ledger, so the files a deactivation or removal gave back to the player still ship. An instance
	 * running a pack has no answer: its files are that pack's.
	 */
	public PackOwnedFiles packOwnedFiles() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState != null && activeState.modpackId != null && !activeState.modpackId.isBlank())
			throw new IOException("This instance runs installed modpack " + activeState.modpackId + ", so it cannot generate a pack of its own");
		PackOwnedFiles.Builder owned = PackOwnedFiles.builder();
		StateHistory.addPackHeldRows(storage, owned);
		for (String modpackId : installedPackIds(activeState, new ClientSelectionStore(storage.selectionFile()))) {
			PackDocument newest = readableNewest(modpackId, activeState);
			if (newest == null) continue;
			for (OwnershipLedger.Entry entry : newest.ownershipLedger().entries().values())
				for (OwnershipLedger.Content content : entry.historicalHashes()) owned.historic(entry.logicalPath(), content.sha1());
		}
		addPendingPaths(owned, activeState);
		for (GeneratedCopyState copies : GeneratedCopyState.loadAll(storage))
			for (GeneratedCopyState.Entry entry : copies.entries()) owned.historic(entry.logicalPath(), entry.sha1());
		return owned.build();
	}

	private PackDocument readableNewest(String modpackId, ClientStorageJsons.ClientGenerationStateFields activeState) {
		try {
			return newestDocument(modpackId, activeState);
		} catch (IOException | RuntimeException unreadable) {
			LOGGER.warn("Modpack {} has no document this instance can read; only the bytes a pack held here stay out of generated packs", modpackId, unreadable);
			return null;
		}
	}

	/**
	 * Every game-directory path of its own generation an update not yet committed plans to hold: the active state still
	 * names the generation before it, and a deferred journal keeps only the operations still owed, so its whole final
	 * state is what says which files it may already have put here. The journal is read without setting anything aside,
	 * since recovery owns it; one that cannot be read leaves out, with a warning, every path any mirrored pack ships.
	 */
	private void addPendingPaths(PackOwnedFiles.Builder owned, ClientStorageJsons.ClientGenerationStateFields activeState) throws IOException {
		Set<String> planned = new TreeSet<>();
		try {
			UpdateJournal.Cursor cursor = new UpdateJournal(storage).readWithoutRepair();
			if (cursor == null) return;
			UpdateTransaction pending = cursor.transaction();
			Set<String> ships = pending.ownershipLedger == null ? Set.of() : shippedPaths(OwnershipLedger.fromFields(pending.ownershipLedger));
			for (UpdatePlan.ProjectedFile row : pending.plan().projectedFinalState()) {
				String path = LogicalPath.normalize(row.relativePath());
				if (row.root() == UpdatePlan.Root.GAME_DIR && row.present() && ships.contains(path)) planned.add(path);
			}
		} catch (IOException | RuntimeException unreadable) {
			LOGGER.warn("The pending update transaction cannot be read; every path a mirrored modpack ships stays out of generated packs", unreadable);
			for (String modpackId : mirroredPackIds()) {
				PackDocument newest = readableNewest(modpackId, activeState);
				if (newest != null) planned.addAll(shippedPaths(newest.ownershipLedger()));
			}
		}
		for (String path : planned) owned.managed(path);
	}

	private static Set<String> shippedPaths(OwnershipLedger ledger) {
		Set<String> paths = new TreeSet<>();
		for (OwnershipLedger.Entry entry : ledger.entries().values())
			if (entry.currentStatus() == OwnershipLedger.Status.PRESENT) paths.add(entry.logicalPath());
		return paths;
	}

	/** The active pack's document: active-state identity and ledger, its mirror entry, and its policy document from the CAS. */
	public Optional<PackDocument> activeDocument() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state == null ? Optional.empty() : Optional.of(activeDocument(state));
	}

	/** The active pack's document, refused when the active pack is not {@code modpackId}, as {@link #hosting(String)} refuses. */
	public PackDocument activeDocument(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null || state.modpackId == null || state.modpackId.isBlank()) throw new IOException("No active modpack is selected");
		if (!state.modpackId.equals(ModpackId.requireValid(modpackId)))
			throw new IOException("The active modpack is " + state.modpackId + ", not the shared " + modpackId);
		return activeDocument(state);
	}

	/** One build per caller: the pointer's own parse and validation are what the cache answers, and the derived document is cheap enough to rebuild. */
	private PackDocument activeDocument(ClientStorageJsons.ClientGenerationStateFields state) throws IOException {
		if (state.manifest != null && HashUtils.isSha1(state.manifestSha1)) {
			try {
				GroupManifest manifest = GroupManifestValidator.validate(state.manifest);
				String policySha1 = HashUtils.normalizeSha1(state.manifestSha1);
				Instant createdAt;
				if (state.createdAt != null && !state.createdAt.isBlank()) {
					createdAt = Instant.parse(state.createdAt);
				} else {
					Optional<JournalEntry> entry = findMirrorEntry(state.modpackId, state.contentToken);
					if (entry.isPresent()) {
						createdAt = entry.get().createdAt();
					} else {
						LOGGER.warn("Active pack {} ({}) has no recorded creation timestamp and no matching journal mirror entry; using unknown creation time", state.modpackId, state.contentToken);
						createdAt = Instant.EPOCH;
					}
				}
				String waitingMusicSha1 = advertisedTrack(state.modpackId, state.contentToken, policySha1);
				return new PackDocument(manifest, state.contentToken, policySha1, createdAt, OwnershipLedger.fromFields(state.ownershipLedger), waitingMusicSha1);
			} catch (IOException e) {
				throw e;
			} catch (RuntimeException e) {
				throw new IOException("Client active state could not be reconstructed: " + state.contentToken, e);
			}
		}
		return document(state.modpackId, mirrorEntry(state.modpackId, state.contentToken), OwnershipLedger.fromFields(state.ownershipLedger), state.manifest, state.manifestSha1);
	}

	/** Whether an active pack pointer exists; {@link #hosting(String)} resolves the rest. */
	public boolean hasActivePack() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state != null && state.modpackId != null && !state.modpackId.isBlank();
	}

	/**
	 * The active pack's full hosting surface: the mirrored head and journal plus every object the policy references, so
	 * joining players get byte-exactly what this client runs. Every mapped file must exist; a missing object means the
	 * host cannot keep its promise and fails loudly instead of starving a download halfway. The caller names the pack
	 * it decided to serve, so an active pack that changed since is refused instead of served in its place.
	 */
	public GenerationHosting hosting(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = requireActive(modpackId);
		Path head = storage.historyHeadFile(state.modpackId);
		Path journal = storage.historyJournalFile(state.modpackId);
		if (!Files.isRegularFile(head, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The active modpack's mirror is incomplete: " + state.modpackId);
		GenerationJsons.HeadDocumentFields headFields = ConfigTools.read(head, GenerationJsons.HeadDocumentFields.class)
				.orElseThrow(() -> new IOException("The active modpack's head document is empty: " + head));
		if (!sameGeneration(state, headFields))
			throw new IOException("The active modpack's mirror head names a different generation than the active state: " + state.modpackId);
		GroupManifest manifest = policyDocument(headFields.policySha1);
		if (!state.modpackId.equals(manifest.modpackId()))
			throw new IOException("The active modpack's head names a different pack: " + manifest.modpackId());
		GenerationHosting hosting = GenerationHosting.of(head, journal, headFields.policySha1, ContentTree.fromManifest(manifest), headFields.waitingMusicSha1, storage::objectFile);
		for (Path path : hosting.asMap().values()) {
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("The active modpack is missing a hosted object: " + path);
		}
		return hosting;
	}

	/**
	 * Whether the mirrored head names exactly the generation the active pointer runs. A mirror can be replaced by
	 * another lineage for the same pack, and then its head describes something this client does not run; serving it
	 * would hand guests a pack the host never installed.
	 */
	private static boolean sameGeneration(ClientStorageJsons.ClientGenerationStateFields state, GenerationJsons.HeadDocumentFields head) {
		if (!HashUtils.isSha1(head.contentToken) || !HashUtils.normalizeSha1(head.contentToken).equals(HashUtils.normalizeSha1(state.contentToken))) return false;
		if (!HashUtils.isSha1(state.manifestSha1)) return true;
		return HashUtils.isSha1(head.policySha1) && HashUtils.normalizeSha1(head.policySha1).equals(HashUtils.normalizeSha1(state.manifestSha1));
	}

	private ClientStorageJsons.ClientGenerationStateFields requireActive(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null || state.modpackId == null || state.modpackId.isBlank()) throw new IOException("No active modpack is selected");
		if (!state.modpackId.equals(ModpackId.requireValid(modpackId)))
			throw new IOException("The active modpack is " + state.modpackId + ", not the shared " + modpackId);
		return state;
	}

	/** Reconstructs the active target from the active document and the persisted selection intent, without server access. */
	public Optional<SelectedModpackTarget> readActiveTarget(ClientPlatform platform) throws IOException {
		Objects.requireNonNull(platform, "platform");
		return activeTarget(storage.readActiveState(), platform);
	}

	/** Same, resolving under the applied selection's own platform: the exact platform the selection last committed under. */
	public Optional<SelectedModpackTarget> readActiveTarget() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return Optional.empty();
		ClientPlatform platform = ClientPlatform.effective(appliedSelection(state, state.modpackId).orElse(null));
		return activeTarget(state, platform);
	}

	private Optional<SelectedModpackTarget> activeTarget(ClientStorageJsons.ClientGenerationStateFields state, ClientPlatform platform) throws IOException {
		if (state == null) return Optional.empty();
		return Optional.of(resolveActive(state, platform));
	}

	/**
	 * The selection the active pointer was committed under: the applied answer to "what is installed", which the
	 * store's desire may already sit ahead of. Pointers written before the selection was recorded fall back to the
	 * store, which was the applied record before the two meanings split.
	 */
	public Optional<SelectionIntent> appliedSelection(String modpackId) throws IOException {
		return appliedSelection(storage.readActiveState(), modpackId);
	}

	private Optional<SelectionIntent> appliedSelection(ClientStorageJsons.ClientGenerationStateFields state, String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		if (state == null || !normalizedModpackId.equals(state.modpackId)) return Optional.empty();
		if (state.selection == null) return new ClientSelectionStore(storage.selectionFile()).get(normalizedModpackId);
		return Optional.of(state.selection.toIntent());
	}

	private SelectedModpackTarget resolveActive(ClientStorageJsons.ClientGenerationStateFields state, ClientPlatform platform) throws IOException {
		Objects.requireNonNull(platform, "platform");
		PackDocument document = activeDocument(state);
		Optional<SelectionIntent> applied = appliedSelection(state, state.modpackId);
		return applied.isPresent()
				? SelectedModpackTarget.prepare(document, null, applied.get(), platform)
				: SelectedModpackTarget.prepareDefault(document, platform);
	}

	/** One pending transaction's target document: the transaction carries the exact ledger, the mirror entry the creation time. */
	public PackDocument document(UpdateTransaction transaction) throws IOException {
		Objects.requireNonNull(transaction, "transaction");
		if (transaction.ownershipLedger == null) throw new IOException("Pending modpack transaction carries no ownership ledger: " + transaction.transactionId);
		return document(transaction.plan().modpackId(), mirrorEntry(transaction.plan().modpackId(), transaction.plan().packTarget().contentToken()), OwnershipLedger.fromFields(transaction.ownershipLedger));
	}

	/** The newest mirror generation of one pack; its ledger comes from the active pointer, else the head mirror, else a witnessed fold. */
	public PackDocument newestDocument(String modpackId) throws IOException {
		return newestDocument(modpackId, storage.readActiveState());
	}

	public PackDocument newestDocument(String modpackId, ClientStorageJsons.ClientGenerationStateFields activeState) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		List<JournalEntry> entries = new JournalMirror(storage).entries(normalizedModpackId);
		if (entries.isEmpty()) throw new IOException("Installed modpack journal mirror is missing: " + normalizedModpackId);
		JournalEntry newest = entries.get(entries.size() - 1);
		if (activeState != null && activeState.modpackId.equals(normalizedModpackId) && activeState.contentToken.equals(newest.contentToken()))
			return document(normalizedModpackId, newest, activeState);
		GenerationJsons.HeadDocumentFields head = new HeadMirror(storage).read(normalizedModpackId);
		if (head != null && head.contentToken != null && !head.contentToken.isBlank() && head.policySha1 != null && !head.policySha1.isBlank()) {
			try {
				if (newest.contentToken().equals(HashUtils.normalizeSha1(head.contentToken)) && newest.policySha1().equals(HashUtils.normalizeSha1(head.policySha1))) return PackDocument.fromFields(head);
			} catch (RuntimeException ignored) {
				// An incomplete or unusable head is not a document; the witnessed fold below is the reconstruction path.
			}
		}
		return document(normalizedModpackId, newest, activeState);
	}

	/** One mirror generation's document: the entry's policy from the CAS, and the exact active-state ledger when that generation is active, else the mirror replay. */
	public PackDocument document(String modpackId, JournalEntry entry) throws IOException {
		return document(modpackId, entry, storage.readActiveState());
	}

	public PackDocument document(String modpackId, JournalEntry entry, ClientStorageJsons.ClientGenerationStateFields activeState) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		if (activeState != null && activeState.modpackId.equals(normalizedModpackId) && activeState.contentToken.equals(entry.contentToken()))
			return document(normalizedModpackId, entry, OwnershipLedger.fromFields(activeState.ownershipLedger));
		return document(normalizedModpackId, entry, replayedLedger(normalizedModpackId, entry));
	}

	/**
	 * Whether one mirror generation can be restored without the server: its policy document sits in the client CAS and every
	 * object of its folded tree is in the CAS or still materialized at its live path. The server hosts only head objects, so
	 * for a non-head generation the client's own bytes are the only source; the plan-time acquisition stays the final judge.
	 */
	public boolean locallyRestorable(String modpackId, JournalEntry entry) throws IOException {
		ModpackId.requireValid(modpackId);
		GroupManifest manifest;
		try {
			manifest = policyDocument(entry.policySha1());
		} catch (IOException unavailable) {
			return false;
		}
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			for (var pathEntry : ContentTree.fromManifest(manifest).files().entrySet()) {
				for (ContentTree.ContentFile file : pathEntry.getValue()) {
					if (FileIntegrity.matchesNamed(storage.objectFile(file.sha1()), file.size(), file.sha1(), cache)) continue;
					if (FileIntegrity.matchesObject(storage.gamePath(pathEntry.getKey()), storage.objectFile(file.sha1()), file.size(), file.sha1(), cache)) continue;
					return false;
				}
			}
		}
		return true;
	}

	private void requirePolicyObject(String hash) throws IOException {
		Path object = storage.objectFile(hash);
		if (!Files.exists(object, LinkOption.NOFOLLOW_LINKS)) throw new MissingGenerationContentException("Client policy document is missing: " + hash);
		if (Files.isSymbolicLink(object) || !Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client policy document is not a regular file: " + object);
	}

	/** One policy document from the client CAS; policy objects are never collected, so witnessed generations stay foldable. */
	public GroupManifest policyDocument(String policySha1) throws IOException {
		String hash = ClientObjectStore.normalizeHash(policySha1);
		requirePolicyObject(hash);
		Path object = storage.objectFile(hash);
		try {
			ModpackJsons.CompleteModpackContentFields fields = ConfigTools.read(object, ModpackJsons.CompleteModpackContentFields.class)
					.orElseThrow(() -> new IOException("Client policy document is empty: " + object));
			return GroupManifestValidator.validate(fields);
		} catch (RuntimeException e) {
			throw new IOException("Client policy document is invalid: " + object, e);
		}
	}

	/** Deletes every retained local artifact for one inactive modpack and collects objects no longer referenced by another pack. */
	public void forgetModpack(String modpackId) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			forgetModpackLocked(modpackId);
			return null;
		});
	}

	private void forgetModpackLocked(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cannot forget a modpack while an update transaction is active");
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState != null && normalizedModpackId.equals(activeState.modpackId)) throw new IOException("Cannot forget the active modpack");
		new ClientSelectionStore(storage.selectionFile()).remove(normalizedModpackId);
		RelayConsent.setRelayed(storage, normalizedModpackId, false);
		GeneratedCopyState.removePack(storage, normalizedModpackId);
		storage.clearOverlay(normalizedModpackId);
		if (!StateHistory.referencedModpackIdsLocked(storage).contains(normalizedModpackId)) FileTrees.delete(storage.historyPackDirectory(normalizedModpackId));
		FileTrees.delete(storage.connectionDirectory(normalizedModpackId));
		ClientObjectStore.collectUnreachableObjects(storage, Set.of());
	}

	Set<String> pruneUnreferencedMirrorsLocked(Set<String> candidates) throws IOException {
		Set<String> timelinePacks = StateHistory.referencedModpackIdsLocked(storage);
		Set<String> archived = new TreeSet<>();
		for (String modpackId : candidates) {
			if (!Files.exists(storage.historyPackDirectory(modpackId), LinkOption.NOFOLLOW_LINKS)) continue;
			if (hasLocalState(modpackId)) continue;
			if (timelinePacks.contains(modpackId)) archived.add(modpackId);
			else FileTrees.delete(storage.historyPackDirectory(modpackId));
		}
		return Set.copyOf(archived);
	}

	/** The boundary marker of one pack's last manual compaction; purely informational state for the storage UI. */
	public record CompactionReceipt(String modpackId, long boundarySeq, Instant compactedAt, long reclaimedObjectCount, long reclaimedObjectBytes) {
		public CompactionReceipt {
			modpackId = ModpackId.requireValid(modpackId);
			compactedAt = Objects.requireNonNull(compactedAt, "compactedAt");
			if (boundarySeq < 1 || reclaimedObjectCount < 0 || reclaimedObjectBytes < 0) throw new IllegalArgumentException("Client compaction receipt values are invalid");
		}
	}

	/** The receipt of one explicitly requested compaction pass: the shared object collection plus every pack's new boundary marker. */
	public record CompactionResult(ClientObjectStore.CollectionResult collection, List<CompactionReceipt> receipts) {
		public CompactionResult {
			collection = Objects.requireNonNull(collection, "collection");
			receipts = List.copyOf(receipts);
		}
	}

	/**
	 * Manually compacts the local history per decision 11: per pack it keeps the active and the newest generation's
	 * content objects plus every mirror entry's policy document, and deletes the content objects only older generations
	 * still name, together with the trimmed generations' generated-copy state. The mirror, the active state, overlays,
	 * and every instance state-history pin are never touched, so a checkpoint the timeline still names cannot lose its
	 * bytes. Refuses while an update transaction is active, and writes each pack's boundary marker after the objects
	 * are reclaimed.
	 */
	public CompactionResult compact() throws IOException {
		return ClientStorageMutation.run(storage, this::compactLocked);
	}

	private CompactionResult compactLocked() throws IOException {
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cannot compact client history while an update transaction is active: " + storage.transactionFile());
		// Fetch caches can go, but a removed pack's mirror is the identity record its retained instance snapshots restore.
		Set<String> archivedMirrors = pruneUnreferencedMirrorsLocked(Set.copyOf(mirroredPackIds()));
		ExpectedSizes kept = new ExpectedSizes();
		List<KeptPack> keptPacks = new ArrayList<>();
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		for (String modpackId : installedPackIds()) {
			List<JournalEntry> entries = new JournalMirror(storage).entries(modpackId);
			Set<String> keptTokens = new TreeSet<>();
			for (JournalEntry entry : entries) if (ClientObjectStore.holdsObject(storage, entry.policySha1())) kept.optional(entry.policySha1(), -1, "kept policy document");
			if (!entries.isEmpty()) {
				JournalEntry newest = entries.get(entries.size() - 1);
				retainGenerationContent(newest, kept);
				keptTokens.add(newest.contentToken());
				keptPacks.add(new KeptPack(modpackId, newest.seq()));
			}
			if (activeState != null && activeState.modpackId.equals(modpackId)) {
				// The sweep pins the active generation from the pointer; only a pointer written before it carried its
				// manifest still needs the mirror to say which objects those are.
				keptTokens.add(activeState.contentToken);
				if (activeState.manifest == null) retainGenerationContent(mirrorEntry(modpackId, activeState.contentToken), kept);
			}
			if (!keptTokens.isEmpty()) removeGeneratedCopies(modpackId, keptTokens);
		}
		for (var claim : ClientObjectStore.collectMirrorClaims(storage, archivedMirrors).entrySet())
			kept.optional(claim.getKey(), claim.getValue(), "timeline-restorable pack history");
		ClientObjectStore.collectNonHistoryReferences(storage, kept);
		ClientObjectStore.CollectionResult collection = ClientObjectStore.collectUnreachableObjects(storage, kept);
		List<CompactionReceipt> receipts = new ArrayList<>();
		for (KeptPack pack : keptPacks) receipts.add(writeCompactionReceipt(pack.modpackId(), pack.boundarySeq(), collection));
		return new CompactionResult(collection, receipts);
	}

	/** The content objects of one kept generation: every file of its folded policy tree. */
	private void retainGenerationContent(JournalEntry entry, ExpectedSizes kept) throws IOException {
		retainGenerationContent(policyDocument(entry.policySha1()), kept);
	}

	private void retainGenerationContent(GroupManifest manifest, ExpectedSizes kept) throws IOException {
		ClientObjectStore.retainGeneration(ContentTree.fromManifest(manifest), kept, "kept generation content");
	}

	/** Deletes the generated-copy state of trimmed generations; it pins objects and only serves generations the compaction no longer keeps. */
	private void removeGeneratedCopies(String modpackId, Set<String> keptTokens) throws IOException {
		GeneratedCopyState.retainPackGenerations(storage, modpackId, keptTokens);
	}

	private CompactionReceipt writeCompactionReceipt(String modpackId, long boundarySeq, ClientObjectStore.CollectionResult collection) throws IOException {
		CompactionReceipt receipt = new CompactionReceipt(modpackId, boundarySeq, Instant.now(), collection.deletedObjectCount(), collection.deletedObjectBytes());
		ClientStorageJsons.ClientCompactionReceiptFields fields = new ClientStorageJsons.ClientCompactionReceiptFields();
		fields.modpackId = receipt.modpackId();
		fields.boundarySeq = receipt.boundarySeq();
		fields.compactedAt = receipt.compactedAt().toString();
		fields.reclaimedObjectCount = receipt.reclaimedObjectCount();
		fields.reclaimedObjectBytes = receipt.reclaimedObjectBytes();
		ConfigTools.writeAtomic(storage.historyCompactionReceiptFile(modpackId), fields);
		return receipt;
	}

	/** The stored boundary marker of one pack's last manual compaction, or empty when the pack was never compacted. */
	public Optional<CompactionReceipt> compactionReceipt(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		Path file = storage.historyCompactionReceiptFile(normalizedModpackId);
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client compaction receipt is not a regular file: " + file);
		ClientStorageJsons.ClientCompactionReceiptFields fields = ConfigTools.read(file, ClientStorageJsons.ClientCompactionReceiptFields.class)
				.orElseThrow(() -> new IOException("Client compaction receipt is empty: " + file));
		if (!normalizedModpackId.equals(fields.modpackId) || fields.boundarySeq < 1 || fields.reclaimedObjectCount < 0 || fields.reclaimedObjectBytes < 0 || fields.compactedAt == null)
			throw new IOException("Client compaction receipt identity is invalid: " + file);
		Instant compactedAt;
		try {
			compactedAt = Instant.parse(fields.compactedAt);
		} catch (RuntimeException e) {
			throw new IOException("Client compaction receipt instant is invalid: " + file, e);
		}
		return Optional.of(new CompactionReceipt(normalizedModpackId, fields.boundarySeq, compactedAt, fields.reclaimedObjectCount, fields.reclaimedObjectBytes));
	}

	private record KeptPack(String modpackId, long boundarySeq) {}

	/** Whether the active pack runs detached: local sovereignty, no forced rewrites, syncing only on an explicit attach. */
	public boolean isDetached(String modpackId) throws IOException {
		return storage.isDetached(modpackId);
	}

	/**
	 * Declining a reviewed advance detaches the active pack: the player refused the server's generation, so nothing
	 * syncs until they attach. A decline without an active state, or of the already-active generation, cannot detach.
	 */
	public void detachOnDeclinedAdvance(String modpackId, String offeredToken) throws IOException {
		String activeToken = activeToken(modpackId);
		if (activeToken == null || activeToken.equals(HashUtils.normalizeSha1(offeredToken))) return;
		declareDetached(modpackId);
	}

	/**
	 * Points the active pointer at the pack the restored tree ran, and nowhere when it ran none. Claims never move: a
	 * restore is a file operation, so the selection store is left exactly as the player last consented it. A pack the
	 * snapshot names but this client no longer claims is not resurrected; a generation whose policy the mirror cannot
	 * rebuild clears the pointer instead of letting it describe a tree it does not carry. The restored pack is detached
	 * because the restored tree is not the head the next join would sync to.
	 */
	boolean reconcileActiveClaim(InstanceTree.LiveIdentity identity) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields current = storage.readActiveState();
		String modpackId = identity.activeModpackId();
		if (modpackId.isEmpty()) return clearActivePointer(current);
		if (!hasLocalState(modpackId)) {
			LOGGER.info("Timeline restore names {} but this client no longer claims it; clearing the active pointer", modpackId);
			return clearActivePointer(current);
		}
		PackDocument document = snapshotDocument(modpackId, identity.contentToken());
		if (document == null) {
			LOGGER.warn("Timeline restore cannot rebuild the {} generation {}; clearing the active pointer so it cannot describe another tree", modpackId, identity.contentToken());
			return clearActivePointer(current);
		}
		SelectionIntent applied = appliedSelection(identity, modpackId);
		if (current != null && modpackId.equals(current.modpackId) && document.contentToken().equals(current.contentToken) && current.detached
				&& current.selection != null && current.selection.toIntent().equals(applied))
			return false;
		storage.writeActiveState(document, applied, true);
		makeProjectionEditable();
		return true;
	}

	private boolean clearActivePointer(ClientStorageJsons.ClientGenerationStateFields current) throws IOException {
		if (current == null) return false;
		storage.clearActiveState();
		return true;
	}

	/** The generation the snapshot ran: its own mirror entry when still retained, the newest generation as the fallback. */
	private PackDocument snapshotDocument(String modpackId, String contentToken) {
		try {
			if (!contentToken.isEmpty()) {
				for (JournalEntry entry : new JournalMirror(storage).entries(modpackId))
					if (entry.contentToken().equals(contentToken)) return document(modpackId, entry);
				LOGGER.info("Timeline snapshot generation {} of {} is no longer in the mirror; pointing at the newest generation", contentToken, modpackId);
			}
			return newestDocument(modpackId);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Cannot rebuild the {} generation {} from the mirror and CAS", modpackId, contentToken, e);
			return null;
		}
	}

	/** The restored tree's own group choice, with the platform the player's stored desire carries; claims are never rewritten here. */
	private SelectionIntent appliedSelection(InstanceTree.LiveIdentity identity, String modpackId) {
		SelectionIntent stored = new ClientSelectionStore(storage.selectionFile()).get(modpackId).orElse(null);
		SelectionIntent applied = identity.selection() == null ? stored : identity.selection();
		if (applied == null) return SelectionIntent.DEFAULTS;
		return stored == null ? applied : applied.withPlatform(stored.platform());
	}

	/**
	 * Declares local sovereignty over the active pack: no sync until an explicit attach, and the active projection
	 * loses its immutability so the owner can edit, delete, and rearrange every file of the kept state. The files are
	 * made private first: a crash between the two leaves an attached pack whose rows are already copies, which the
	 * next convergence re-links, where the other order would leave a detached pack that silently stays sealed.
	 */
	public void declareDetached(String modpackId) throws IOException {
		boolean wasDetached = storage.isDetached(modpackId);
		unprotectProjection();
		storage.setDetached(modpackId, true);
		if (!wasDetached) LOGGER.info("Modpack {} is detached: every file stays as it is and server updates stop until you resume syncing", modpackId);
	}

	/**
	 * Makes the active projection editable without touching the pointer: each file that still shares its inode with a
	 * CAS object gets a private copy first, then the read-only policy drops. Timeline restore publishes a generation and
	 * then calls this, so the restored files behave like any other detached pack's files.
	 */
	public void makeProjectionEditable() throws IOException {
		unprotectProjection();
	}

	/**
	 * One pass that puts projection ownership back where the durable flag says it belongs, and does nothing when it
	 * already is. Attaching re-seals what a detach left private; detaching frees what an attach left sealed. Every
	 * attach exit and every convergence calls it, so a crash in either direction is repaired by the next run instead
	 * of leaving a half-owned projection behind.
	 */
	public void reconcileProjectionOwnership() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		if (active == null) return;
		Path activeDirectory = storage.activeDirectory();
		if (!Files.isDirectory(activeDirectory, LinkOption.NOFOLLOW_LINKS)) return;
		if (FileTrees.isRedirectingLink(activeDirectory)) {
			LOGGER.warn("Leaving the active projection as it is: the managed root is a symbolic link: {}", activeDirectory);
			return;
		}
		if (active.detached) unprotectProjection();
		else resealProjection();
	}

	/**
	 * Detach: every projection file that still shares an inode with a CAS object becomes a private copy, and every
	 * file loses the read-only policy. A file that cannot be taken fails loudly - a half-detached pack would keep
	 * sealing files the player was promised, and the object behind a shared inode would stay editable.
	 */
	private void unprotectProjection() throws IOException {
		Path active = storage.activeDirectory();
		if (!Files.isDirectory(active, LinkOption.NOFOLLOW_LINKS)) return;
		if (FileTrees.isRedirectingLink(active)) {
			LOGGER.warn("Leaving the active projection protected: the managed root is a symbolic link: {}", active);
			return;
		}
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			Files.walkFileTree(active, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
					// A junction is a directory to Java but opens its target; skipping it keeps every external file out
					// of the detach, which only ever speaks for files this projection actually owns.
					if (!directory.equals(active) && FileTrees.isRedirectingLink(directory)) return FileVisitResult.SKIP_SUBTREE;
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
					if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
					breakSharedObjectLink(file, cache);
					ImmutableFiles.unprotect(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
					throw failure;
				}
			});
		}
	}

	/**
	 * Attach: every projection file whose bytes are a CAS object again becomes that object's hard link, so the store
	 * and the projection share one inode and one sealed policy again. A row the store holds no copy of is the player's
	 * own file or a generation this client never fetched; it is left alone and the next convergence decides it.
	 */
	private void resealProjection() throws IOException {
		Path active = storage.activeDirectory();
		int relinked = 0;
		int keptCopies = 0;
		IOException firstLinkFailure = null;
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			for (Path file : projectionFiles(active)) {
				try {
					// A sealed file cannot be rewritten in place, so the immutable tripwire answers it from one stat; an
					// unsealed copy is the player's until this pass re-links it, so it needs the one that also proves a
					// same-tick rewrite before its bytes are allowed to become an object's.
					String hash = ImmutableFiles.isProtected(file) ? cache.getOrComputeHashImmutable(file) : cache.getOrComputeHashEditable(file);
					Path object = HashUtils.isSha1(hash) ? storage.objectFile(HashUtils.normalizeSha1(hash)) : null;
					if (object == null || !Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) continue;
					if (FileIntegrity.sameInode(file, object)) {
						ImmutableFiles.protect(file);
						continue;
					}
					IOException failure = relink(file, object);
					if (failure == null) {
						relinked++;
						continue;
					}
					keptCopies++;
					if (firstLinkFailure == null) firstLinkFailure = failure;
					ImmutableFiles.protect(file);
				} catch (IOException e) {
					LOGGER.warn("Cannot re-seal {}: it stays the file it is until the next sync", file, e);
				}
			}
		}
		if (relinked > 0) LOGGER.info("Re-linked {} projection files to the shared store after the pack was attached again", relinked);
		if (keptCopies > 0)
			LOGGER.warn("{} projection files stay verified copies: the store and the projection cannot share a hard link ({})", keptCopies, firstLinkFailure);
	}

	/** The regular files of the active projection, with every unreadable entry named rather than skipped silently. */
	private List<Path> projectionFiles(Path active) throws IOException {
		List<Path> files = new ArrayList<>();
		Files.walkFileTree(active, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
				if (!directory.equals(active) && FileTrees.isRedirectingLink(directory)) return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				if (attributes.isRegularFile()) files.add(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException failure) {
				LOGGER.warn("Cannot read {} while re-sealing the projection: it stays as it is", file, failure);
				return FileVisitResult.CONTINUE;
			}
		});
		return files;
	}

	/** Publishes {@code object} as a hard link over {@code file}, or returns why this store pair cannot link. */
	private static IOException relink(Path file, Path object) {
		Path temporary = file.resolveSibling("." + file.getFileName() + DurableFiles.TEMPORARY_SUFFIX);
		try {
			Files.deleteIfExists(temporary);
			Files.createLink(temporary, object);
		} catch (IOException | UnsupportedOperationException unsupported) {
			return unsupported instanceof IOException failure ? failure : new IOException("This filesystem cannot create hard links", unsupported);
		}
		try {
			DurableFiles.replace(temporary, file);
			return null;
		} catch (IOException failed) {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException ignored) {
				// The temporary of a failed replace stays as evidence; the file itself is untouched.
			}
			return failed;
		}
	}

	private void breakSharedObjectLink(Path file, FileCache cache) throws IOException {
		String hash = cache.getHashOrNull(file);
		if (hash == null) hash = HashUtils.hashFile(file);
		if (!HashUtils.isSha1(hash)) return;
		hash = HashUtils.normalizeSha1(hash);
		Path object = storage.objectFile(hash);
		if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS) || !FileIntegrity.sameInode(file, object)) return;
		VerifiedFileTransfer.copyBreak(object, file, Files.size(file), hash, cache);
	}

	/**
	 * Whether the server head is the generation the active state already points at. Token equality says nothing about
	 * content: a detached pack may have edited any file of its generation, so this only informs the join prompt and
	 * never moves the flag. Detachment ends only by an explicit attach.
	 */
	public boolean headMatchesActive(String modpackId, String headToken) throws IOException {
		String activeToken = activeToken(modpackId);
		return activeToken != null && activeToken.equals(HashUtils.normalizeSha1(headToken));
	}

	private String activeToken(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state != null && state.modpackId.equals(ModpackId.requireValid(modpackId)) ? state.contentToken : null;
	}

	private PackDocument document(String modpackId, JournalEntry entry, OwnershipLedger ledger) throws IOException {
		return document(modpackId, entry, ledger, null, null);
	}

	/**
	 * One generation's document. {@code carriedManifest} and {@code carriedPolicySha1} are the policy document the
	 * caller already holds in durable state; without them the content-addressed copy is the only source, and a pointer
	 * that outlives that copy cannot describe what the client runs. Both travel together because a mirror can carry
	 * several generations under one content token: pairing this manifest with another entry's hash would be a document
	 * that never existed.
	 */
	private PackDocument document(String modpackId, JournalEntry entry, OwnershipLedger ledger, ModpackJsons.CompleteModpackContentFields carriedManifest, String carriedPolicySha1) throws IOException {
		try {
			GroupManifest manifest = carriedManifest == null ? policyDocument(entry.policySha1()) : GroupManifestValidator.validate(carriedManifest);
			return new PackDocument(manifest, entry.contentToken(), carriedManifest == null ? entry.policySha1() : carriedPolicySha1, entry.createdAt(), ledger, advertisedTrack(modpackId, entry));
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Client generation could not be reconstructed from the journal mirror: " + entry.contentToken(), e);
		}
	}

	/** The advertised track's hash when the head mirror names this exact generation; the mirror is the only offline record of the track. */
	private String advertisedTrack(String modpackId, JournalEntry entry) throws IOException {
		return advertisedTrack(modpackId, entry.contentToken(), entry.policySha1());
	}

	private String advertisedTrack(String modpackId, String contentToken, String policySha1) throws IOException {
		GenerationJsons.HeadDocumentFields head = new HeadMirror(storage).read(modpackId);
		return head != null && contentToken.equals(head.contentToken) && policySha1.equals(head.policySha1) ? head.waitingMusicSha1 : "";
	}

	/**
	 * One mirror can carry several generations per content token: a policy-only publish changes the policy document
	 * while the file tree - and with it the token - stays put. Resolution is newest-first and prefers the newest entry
	 * whose policy document the client witnessed, because the CAS only ever stores policies of fetched heads and older
	 * same-token policies may never have existed locally. An entry whose policy object is absent can never silently
	 * stand in for one that is present; when nothing reconstructs, the newest token match is returned for its
	 * missing-object error, naming the policy sha1 the server currently serves.
	 */
	private Optional<JournalEntry> findMirrorEntry(String modpackId, String contentToken) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		String normalizedToken = HashUtils.normalizeSha1(contentToken);
		JournalEntry newest = null;
		JournalEntry newestWitnessed = null;
		for (JournalEntry entry : new JournalMirror(storage).entries(normalizedModpackId)) {
			if (!entry.contentToken().equals(normalizedToken)) continue;
			newest = entry;
			if (Files.exists(storage.objectFile(ClientObjectStore.normalizeHash(entry.policySha1())), LinkOption.NOFOLLOW_LINKS)) newestWitnessed = entry;
		}
		if (newestWitnessed != null) return Optional.of(newestWitnessed);
		return Optional.ofNullable(newest);
	}

	private JournalEntry mirrorEntry(String modpackId, String contentToken) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		String normalizedToken = HashUtils.normalizeSha1(contentToken);
		return findMirrorEntry(normalizedModpackId, normalizedToken)
				.orElseThrow(() -> new IOException("The journal mirror has no entry for generation " + normalizedToken + ": " + normalizedModpackId));
	}

	/**
	 * The mirror-replay ledger for one entry: policy documents this client has in CAS are folded forward. Entries whose
	 * policy was never fetched (prefix or hole) are skipped; the server hosts only the current head, so those objects
	 * cannot appear later. The target itself must be witnessed.
	 */
	private OwnershipLedger replayedLedger(String modpackId, JournalEntry target) throws IOException {
		OwnershipLedger ledger = null;
		int unwitnessed = 0;
		for (JournalEntry entry : new JournalMirror(storage).entries(modpackId)) {
			GroupManifest manifest;
			try {
				manifest = policyDocument(entry.policySha1());
			} catch (MissingGenerationContentException unavailable) {
				if (entry.seq() == target.seq()) {
					if (ledger == null)
						throw new MissingGenerationContentException("No stored policy document reconstructs generation " + target.contentToken() + " of " + modpackId + "; its " + (unwitnessed + 1)
								+ " journal entries are history this client never fetched, and one sync stores the newest");
					throw unavailable;
				}
				unwitnessed++;
				continue;
			}
			if (!modpackId.equals(manifest.modpackId())) throw new IOException("Cached policy document belongs to another modpack: " + entry.policySha1());
			ledger = OwnershipLedger.materialize(ledger == null ? OwnershipLedger.empty(manifest.modpackId()) : ledger, manifest);
			if (entry.seq() == target.seq()) return ledger;
		}
		// Nothing folded at all means no entry's policy document is on this machine, which is an unsynced pack rather
		// than a damaged mirror. Saying "the mirror never reaches the entry" points the reader at the wrong file.
		if (ledger == null)
			throw new MissingGenerationContentException("No stored policy document reconstructs generation " + target.contentToken()
					+ " of " + modpackId + "; its " + unwitnessed + " journal entries are history this client never fetched, and one sync stores the newest");
		throw new IOException("The journal mirror holds no entry " + target.seq() + " for " + modpackId);
	}
}
