package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Reconstructs pack generations offline: the journal mirror supplies a generation's identity, the client CAS its
 * policy document, and the active-state pointer or the pending transaction its ownership ledger. The mirror is the
 * only history store; per-generation records are gone.
 */
public final class ClientGenerationStore {
	private final ClientStorage storage;

	public ClientGenerationStore(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage);
	}

	/** Every modpack id with a local journal mirror; a mirror exists exactly when the pack has been fetched from a server. */
	public List<String> installedPackIds() throws IOException {
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

	/**
	 * Whether this pack ever committed locally: it holds the active pointer or a stored group selection. The mirror
	 * cannot answer this, because it is already fetched when the first install's review starts. Deactivation keeps the
	 * selection, so a deactivated pack is still not a first install; removal forgets both.
	 */
	public boolean hasLocalState(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state != null && normalizedModpackId.equals(state.modpackId)) return true;
		return new ClientSelectionStore(storage.selectionFile()).get(normalizedModpackId).isPresent();
	}

	/** The active pack's document: active-state identity and ledger, its mirror entry, and its policy document from the CAS. */
	public Optional<PackDocument> activeDocument() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state == null ? Optional.empty() : Optional.of(document(mirrorEntry(state.modpackId, state.contentToken), OwnershipLedger.fromFields(state.ownershipLedger)));
	}

	/** Reconstructs the active target from the active document and the persisted selection intent, without server access. */
	public Optional<SelectedModpackTarget> readActiveTarget(ClientPlatform platform) throws IOException {
		Objects.requireNonNull(platform, "platform");
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return Optional.empty();
		PackDocument document = document(mirrorEntry(state.modpackId, state.contentToken), OwnershipLedger.fromFields(state.ownershipLedger));
		Optional<SelectionIntent> stored = new ClientSelectionStore(storage.selectionFile()).get(state.modpackId);
		return Optional.of(stored.isPresent()
				? SelectedModpackTarget.prepare(document, null, stored.get(), platform)
				: SelectedModpackTarget.prepareDefault(document, platform));
	}

	/** One pending transaction's target document: the transaction carries the exact ledger, the mirror entry the creation time. */
	public PackDocument document(UpdateTransaction transaction) throws IOException {
		Objects.requireNonNull(transaction, "transaction");
		if (transaction.ownershipLedger == null) throw new IOException("Pending modpack transaction carries no ownership ledger: " + transaction.transactionId);
		return document(mirrorEntry(transaction.modpackId, transaction.contentToken), OwnershipLedger.fromFields(transaction.ownershipLedger));
	}

	/** The newest mirror generation of one pack; its ledger comes from the active pointer when that is the newest generation. */
	public PackDocument newestDocument(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		List<JournalEntry> entries = new JournalMirror(storage).entries(normalizedModpackId);
		if (entries.isEmpty()) throw new IOException("Installed modpack journal mirror is missing: " + normalizedModpackId);
		JournalEntry newest = entries.get(entries.size() - 1);
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state != null && state.modpackId.equals(normalizedModpackId) && state.contentToken.equals(newest.contentToken()))
			return document(newest, OwnershipLedger.fromFields(state.ownershipLedger));
		return document(newest, replayedLedger(normalizedModpackId, newest));
	}

	/** One cached policy document from the client CAS; policy objects are never collected, so witnessed generations stay foldable. */
	public GroupManifest policyDocument(String policySha1) throws IOException {
		String hash = ClientObjectStore.normalizeHash(policySha1);
		Path object = storage.objectFile(hash);
		if (!Files.exists(object, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client policy document is missing: " + hash);
		if (Files.isSymbolicLink(object) || !Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client policy document is not a regular file: " + object);
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
		ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
		SelectionIntent expectedSelection = selections.get(normalizedModpackId).orElse(null);
		selections.remove(normalizedModpackId, expectedSelection);
		FileTrees.delete(storage.generatedCopiesPackDirectory(normalizedModpackId));
		storage.clearOverlay(normalizedModpackId);
		FileTrees.delete(storage.baselineFile(normalizedModpackId).getParent());
		FileTrees.delete(storage.historyPackDirectory(normalizedModpackId));
		FileTrees.delete(storage.connectionDirectory(normalizedModpackId));
		ClientObjectStore.collectUnreachableObjects(storage, Set.of());
	}

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
		storage.setDetached(modpackId, true);
	}

	/** The head catching up with the active generation dissolves detachment silently; anything else leaves the flag alone. */
	public void observeHeadToken(String modpackId, String headToken) throws IOException {
		String activeToken = activeToken(modpackId);
		if (activeToken != null && activeToken.equals(HashUtils.normalizeSha1(headToken))) storage.setDetached(modpackId, false);
	}

	private String activeToken(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state != null && state.modpackId.equals(ModpackId.requireValid(modpackId)) ? state.contentToken : null;
	}

	private PackDocument document(JournalEntry entry, OwnershipLedger ledger) throws IOException {
		try {
			return new PackDocument(policyDocument(entry.policySha1()), entry.contentToken(), entry.policySha1(), entry.createdAt(), ledger);
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Client generation could not be reconstructed from the journal mirror: " + entry.contentToken(), e);
		}
	}

	private JournalEntry mirrorEntry(String modpackId, String contentToken) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		String normalizedToken = HashUtils.normalizeSha1(contentToken);
		for (JournalEntry entry : new JournalMirror(storage).entries(normalizedModpackId))
			if (entry.contentToken().equals(normalizedToken)) return entry;
		throw new IOException("The journal mirror has no entry for generation " + normalizedToken + ": " + normalizedModpackId);
	}

	/**
	 * The mirror-replay ledger for one entry: policy documents are folded forward exactly like the server replays its
	 * journal, starting from the first entry whose policy object is in the client CAS. History the client never
	 * witnessed cannot contribute hashes or groups, so the replay is exact for the active generation and a stable,
	 * conservative ownership view for older ones.
	 */
	private OwnershipLedger replayedLedger(String modpackId, JournalEntry target) throws IOException {
		OwnershipLedger ledger = null;
		for (JournalEntry entry : new JournalMirror(storage).entries(modpackId)) {
			GroupManifest manifest;
			try {
				manifest = policyDocument(entry.policySha1());
			} catch (IOException unavailable) {
				if (ledger != null) throw new IOException("The journal mirror policy history has a gap at entry " + entry.seq() + ": " + modpackId, unavailable);
				continue;
			}
			if (!modpackId.equals(manifest.modpackId())) throw new IOException("Cached policy document belongs to another modpack: " + entry.policySha1());
			ledger = OwnershipLedger.materialize(ledger == null ? OwnershipLedger.empty(manifest.modpackId()) : ledger, manifest);
			if (entry.seq() == target.seq()) return ledger;
		}
		throw new IOException("The journal mirror never reaches entry " + target.seq() + " with its policy documents: " + modpackId);
	}
}
