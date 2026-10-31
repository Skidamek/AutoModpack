package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.ContentTree;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The active pack pointer: the one durable answer to what this client runs, and the detach flag that rides on it.
 *
 * <p>
 * It is unique state, so unusable content fails the boot in place and is never set aside: continuing as empty would
 * silently rejoin server enforcement. The {@link PackDocument} is the single write argument on purpose - pack,
 * generation, ledger and carried manifest describe one generation, and taking them apart once let a commit record a
 * pointer naming one thing while running another.
 */
final class ActivePackPointer {
	private final Path stateFile;

	ActivePackPointer(Path stateFile) {
		this.stateFile = Objects.requireNonNull(stateFile, "state file");
	}

	/** Why selected and the pointer disagree, or null when the selection may follow the pointer (matching, or no pointer). */
	static String selectionMismatch(ClientConfigJsons.SelectedModpackFields selected, ClientStorageJsons.ClientGenerationStateFields state) {
		return selectionMismatch(selected == null ? null : selected.modpackId, state);
	}

	static String selectionMismatch(String followId, ClientStorageJsons.ClientGenerationStateFields state) {
		if (followId == null) followId = "";
		if (followId.isBlank()) return state == null ? null : "no modpack is selected";
		if (!ModpackId.isValid(followId)) return "the configured selected modpack ID is invalid: " + followId;
		if (state == null) return "the active state is missing";
		if (!followId.equals(state.modpackId)) return "the active state belongs to " + state.modpackId + ", but the selected modpack is " + followId;
		return null;
	}

	/** The pointer, or null when none was persisted yet. */
	ClientStorageJsons.ClientGenerationStateFields read() throws IOException {
		return ConfigTools.readUnique(stateFile, ClientStorageJsons.ClientGenerationStateFields.class, "Client active state", ActivePackPointer::validated).orElse(null);
	}

	static ClientStorageJsons.ClientGenerationStateFields validated(ClientStorageJsons.ClientGenerationStateFields state) {
		if (!ModpackId.isValid(state.modpackId) || !HashUtils.isSha1(state.contentToken) || !"ACTIVE".equals(state.status))
			throw new IllegalArgumentException("Client active state identity is invalid");
		OwnershipLedger.fromFields(state.ownershipLedger);
		if (!state.modpackId.equals(state.ownershipLedger.modpackId)) throw new IllegalArgumentException("Client active state and its ledger belong to different modpacks");
		// A carried manifest has to be the generation this pointer names, or the pointer would describe something it does not run.
		if (state.manifest != null && !ContentTree.fromManifest(GroupManifestValidator.validate(state.manifest)).token().equals(state.contentToken))
			throw new IllegalArgumentException("Client active state carries a manifest that is not its own generation");
		return state;
	}

	/** Publishes the pointer for what the client now runs, keeping the pack's current detachment. */
	void write(PackDocument document, SelectionIntent applied) throws IOException {
		write(applied, base(document));
	}

	/** Publishes a generation with the detachment chosen by an explicit state transition. */
	void write(PackDocument document, SelectionIntent applied, boolean detached) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = base(document);
		state.detached = detached;
		write(applied, state);
	}

	/** Stages a pointer with no recorded selection: the pre-split shape, whose applied answer falls back to the store. */
	void write(PackDocument document) throws IOException {
		publish(base(document));
	}

	/** Clears the pointer: the client runs no active pack. */
	void clear() throws IOException {
		Files.deleteIfExists(stateFile);
	}

	/** Whether the active pack runs detached: local sovereignty over server enforcement. Packs without the pointer never are. */
	boolean isDetached(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = read();
		return state != null && state.modpackId.equals(ModpackId.requireValid(modpackId)) && state.detached;
	}

	/** Sets the detachment flag; a no-op for packs without the pointer, since the flag lives there. */
	void setDetached(String modpackId, boolean detached) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		ClientStorageJsons.ClientGenerationStateFields state = read();
		if (state == null || !state.modpackId.equals(normalizedModpackId) || state.detached == detached) return;
		state.detached = detached;
		Files.createDirectories(stateFile.getParent());
		ConfigTools.writeAtomic(stateFile, state);
	}

	private void write(SelectionIntent applied, ClientStorageJsons.ClientGenerationStateFields state) throws IOException {
		SelectionIntent selection = Objects.requireNonNull(applied, "applied selection");
		state.selectedGroups = List.copyOf(selection.requestedGroups());
		state.selectedCategories = List.copyOf(selection.requestedCategories());
		state.excludedGroups = List.copyOf(selection.excludedGroups());
		state.selectedPlatform = selection.platform() == null ? null : selection.platform().id();
		publish(state);
	}

	private ClientStorageJsons.ClientGenerationStateFields base(PackDocument document) throws IOException {
		Objects.requireNonNull(document, "installed document");
		ClientStorageJsons.ClientGenerationStateFields state = new ClientStorageJsons.ClientGenerationStateFields();
		state.modpackId = ModpackId.requireValid(document.manifest().modpackId());
		state.contentToken = HashUtils.requireDigest(document.contentToken(), "generation ID");
		state.ownershipLedger = document.ownershipLedger().toFields();
		state.manifest = document.manifest().toFields();
		state.manifestSha1 = document.policySha1();
		state.detached = currentDetachmentFor(state.modpackId);
		return state;
	}

	private void publish(ClientStorageJsons.ClientGenerationStateFields state) throws IOException {
		Files.createDirectories(stateFile.getParent());
		ConfigTools.writeAtomic(stateFile, state);
	}

	/**
	 * The persistent detachment flag of the active pack: commits republish the same pack's sovereignty, so only an
	 * explicit attach or a cleared state may end it. An unreadable pointer fails the write instead of clearing the flag.
	 */
	private boolean currentDetachmentFor(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields current = read();
		return current != null && current.modpackId.equals(modpackId) && current.detached;
	}
}
