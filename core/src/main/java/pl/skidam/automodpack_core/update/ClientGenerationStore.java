package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.ContentTree;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance.ExpectedSizes;
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
	private final ClientStorage storage;

	public ClientGenerationStore(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage);
	}

	/** Every modpack id with a local journal mirror, consent or not: the mirror is cache a head fetch writes, never a record of an install. */
	public List<String> mirroredPackIds() throws IOException {
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

	/** The active pack's document: active-state identity and ledger, its mirror entry, and its policy document from the CAS. */
	public Optional<PackDocument> activeDocument() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state == null ? Optional.empty() : Optional.of(document(state.modpackId, mirrorEntry(state.modpackId, state.contentToken), OwnershipLedger.fromFields(state.ownershipLedger)));
	}

	/** Whether an active pack pointer exists; {@link #hosting()} resolves the rest. */
	public boolean hasActivePack() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state != null && state.modpackId != null && !state.modpackId.isBlank();
	}

	/**
	 * The active pack's full hosting surface: the mirrored head and journal plus every object the policy references, so
	 * joining players get byte-exactly what this client runs. Every mapped file must exist; a missing object means the
	 * host cannot keep its promise and fails loudly instead of starving a download halfway.
	 */
	public GenerationHosting hosting() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null || state.modpackId == null || state.modpackId.isBlank()) throw new IOException("No active modpack is selected");
		Path head = storage.historyHeadFile(state.modpackId);
		Path journal = storage.historyJournalFile(state.modpackId);
		if (!Files.isRegularFile(head, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The active modpack's mirror is incomplete: " + state.modpackId);
		GenerationJsons.HeadDocumentFields headFields = ConfigTools.read(head, GenerationJsons.HeadDocumentFields.class)
				.orElseThrow(() -> new IOException("The active modpack's head document is empty: " + head));
		GroupManifest manifest = policyDocument(headFields.policySha1);
		if (!state.modpackId.equals(manifest.modpackId()))
			throw new IOException("The active modpack's head names a different pack: " + manifest.modpackId());
		GenerationHosting hosting = GenerationHosting.of(head, journal, headFields.policySha1, ContentTree.fromManifest(manifest), headFields.waitingMusicSha1, storage::objectFile);
		for (Path path : hosting.asMap().values()) {
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("The active modpack is missing a hosted object: " + path);
		}
		return hosting;
	}

	/** Reconstructs the active target from the active document and the persisted selection intent, without server access. */
	public Optional<SelectedModpackTarget> readActiveTarget(ClientPlatform platform) throws IOException {
		Objects.requireNonNull(platform, "platform");
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return Optional.empty();
		return Optional.of(resolveActive(state, platform));
	}

	/** Same, resolving under the applied selection's own platform: the exact platform the selection last committed under. */
	public Optional<SelectedModpackTarget> readActiveTarget() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return Optional.empty();
		ClientPlatform platform = ClientPlatform.effective(appliedSelection(state.modpackId).orElse(null));
		return Optional.of(resolveActive(state, platform));
	}

	/**
	 * The selection the active pointer was committed under: the applied answer to "what is installed", which the
	 * store's desire may already sit ahead of. Pointers written before the selection was recorded fall back to the
	 * store, which was the applied record before the two meanings split.
	 */
	public Optional<SelectionIntent> appliedSelection(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null || !normalizedModpackId.equals(state.modpackId)) return Optional.empty();
		if (state.selectedGroups == null) return new ClientSelectionStore(storage.selectionFile()).get(normalizedModpackId);
		return Optional.of(new SelectionIntent(state.selectedGroups, state.selectedCategories, state.excludedGroups,
				state.selectedPlatform == null ? null : ClientPlatform.parse(state.selectedPlatform)));
	}

	private SelectedModpackTarget resolveActive(ClientStorageJsons.ClientGenerationStateFields state, ClientPlatform platform) throws IOException {
		Objects.requireNonNull(platform, "platform");
		PackDocument document = document(state.modpackId, mirrorEntry(state.modpackId, state.contentToken), OwnershipLedger.fromFields(state.ownershipLedger), state.manifest, state.manifestSha1);
		Optional<SelectionIntent> applied = appliedSelection(state.modpackId);
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
			for (var file : ContentTree.fromManifest(manifest).files().entrySet()) {
				if (FileIntegrity.matchesNamed(storage.objectFile(file.getValue().sha1()), file.getValue().size(), file.getValue().sha1(), cache)) continue;
				if (FileIntegrity.matchesObject(storage.gamePath(file.getKey()), storage.objectFile(file.getValue().sha1()), file.getValue().size(), file.getValue().sha1(), cache))
					continue;
				return false;
			}
		}
		return true;
	}

	/*
	 * Witnessed policies are immutable content-addressed objects - their file name is their hash and the store never
	 * rewrites them - so one parse serves every join, fold, and history screen. The outer key is the client directory:
	 * two storages can witness the same hash while one of them has deleted its copy, and a hit must never stand in for
	 * a missing object. A wholesale clear past the cap keeps each storage's map bounded against a server that never
	 * stops publishing; forgetting a pack clears the storage's map because that is the one path that may collect a
	 * policy object.
	 */
	private static final int POLICY_MEMO_CAP = 64;
	private static final Map<Path, Map<String, GroupManifest>> POLICY_MEMOS = new ConcurrentHashMap<>();

	private void requirePolicyObject(String hash) throws IOException {
		Path object = storage.objectFile(hash);
		if (!Files.exists(object, LinkOption.NOFOLLOW_LINKS)) throw new MissingGenerationContentException("Client policy document is missing: " + hash);
		if (Files.isSymbolicLink(object) || !Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client policy document is not a regular file: " + object);
	}

	/** One cached policy document from the client CAS; policy objects are never collected, so witnessed generations stay foldable. */
	public GroupManifest policyDocument(String policySha1) throws IOException {
		String hash = ClientObjectStore.normalizeHash(policySha1);
		Map<String, GroupManifest> memo = POLICY_MEMOS.computeIfAbsent(storage.clientDirectory().toAbsolutePath().normalize(), ignored -> new ConcurrentHashMap<>());
		GroupManifest witnessed = memo.get(hash);
		if (witnessed != null) {
			// A hit still proves presence: a collected or deleted object must read as missing, never as a remembered parse.
			requirePolicyObject(hash);
			return witnessed;
		}
		requirePolicyObject(hash);
		Path object = storage.objectFile(hash);
		try {
			ModpackJsons.CompleteModpackContentFields fields = ConfigTools.read(object, ModpackJsons.CompleteModpackContentFields.class)
					.orElseThrow(() -> new IOException("Client policy document is empty: " + object));
			GroupManifest manifest = GroupManifestValidator.validate(fields);
			if (memo.size() >= POLICY_MEMO_CAP) memo.clear();
			memo.put(hash, manifest);
			return manifest;
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
		POLICY_MEMOS.remove(storage.clientDirectory().toAbsolutePath().normalize());
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cannot forget a modpack while an update transaction is active");
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState != null && normalizedModpackId.equals(activeState.modpackId)) throw new IOException("Cannot forget the active modpack");
		new ClientSelectionStore(storage.selectionFile()).remove(normalizedModpackId);
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
		for (String modpackId : installedPackIds()) {
			List<JournalEntry> entries = new JournalMirror(storage).entries(modpackId);
			if (entries.isEmpty()) continue;
			for (JournalEntry entry : entries) kept.optional(entry.policySha1(), -1, "kept policy document");
			JournalEntry newest = entries.get(entries.size() - 1);
			retainGenerationContent(newest, kept);
			Set<String> keptTokens = new TreeSet<>(Set.of(newest.contentToken()));
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state != null && state.modpackId.equals(modpackId)) {
				JournalEntry active = mirrorEntry(modpackId, state.contentToken);
				retainGenerationContent(active, kept);
				keptTokens.add(active.contentToken());
			}
			removeGeneratedCopies(modpackId, keptTokens);
			keptPacks.add(new KeptPack(modpackId, newest.seq()));
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
		for (var file : ContentTree.fromManifest(policyDocument(entry.policySha1())).files().entrySet())
			kept.optional(file.getValue().sha1(), file.getValue().size(), "kept generation content");
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
	 * Declares local sovereignty over the active pack: no sync until an explicit attach, and the active projection
	 * loses its immutability so the owner can edit, delete, and rearrange every file of the kept state.
	 */
	public void declareDetached(String modpackId) throws IOException {
		boolean wasDetached = storage.isDetached(modpackId);
		storage.setDetached(modpackId, true);
		unprotectProjection();
		if (!wasDetached) LOGGER.info("Modpack {} is detached: every file stays as it is and server updates stop until you resume syncing", modpackId);
	}

	private void unprotectProjection() throws IOException {
		Path active = storage.activeDirectory();
		if (!Files.isDirectory(active, LinkOption.NOFOLLOW_LINKS)) return;
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory()); var paths = Files.walk(active)) {
			for (Path file : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
				breakSharedObjectLink(file, cache);
				ImmutableFiles.unprotect(file);
			}
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
		GenerationJsons.HeadDocumentFields head = new HeadMirror(storage).read(modpackId);
		return head != null && entry.contentToken().equals(head.contentToken) && entry.policySha1().equals(head.policySha1) ? head.waitingMusicSha1 : "";
	}

	/**
	 * One mirror can carry several generations per content token: a policy-only publish changes the policy document
	 * while the file tree - and with it the token - stays put. Resolution is newest-first and prefers the newest entry
	 * whose policy document the client witnessed, because the CAS only ever stores policies of fetched heads and older
	 * same-token policies may never have existed locally. An entry whose policy object is absent can never silently
	 * stand in for one that is present; when nothing reconstructs, the newest token match is returned for its
	 * missing-object error, naming the policy sha1 the server currently serves.
	 */
	private JournalEntry mirrorEntry(String modpackId, String contentToken) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		String normalizedToken = HashUtils.normalizeSha1(contentToken);
		JournalEntry newest = null;
		JournalEntry newestWitnessed = null;
		for (JournalEntry entry : new JournalMirror(storage).entries(normalizedModpackId)) {
			if (!entry.contentToken().equals(normalizedToken)) continue;
			newest = entry;
			if (Files.exists(storage.objectFile(ClientObjectStore.normalizeHash(entry.policySha1())), LinkOption.NOFOLLOW_LINKS)) newestWitnessed = entry;
		}
		if (newestWitnessed != null) return newestWitnessed;
		if (newest != null) return newest;
		throw new IOException("The journal mirror has no entry for generation " + normalizedToken + ": " + normalizedModpackId);
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
