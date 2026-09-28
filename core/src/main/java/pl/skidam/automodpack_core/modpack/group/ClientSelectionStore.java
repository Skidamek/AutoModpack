package pl.skidam.automodpack_core.modpack.group;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.SelectionJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;

/**
 * The persisted per-modpack group selection: the player's declared desire, written at every consent moment.
 *
 * <p>
 * Writers must hold the game-directory mutation lock, like every other durable state file; the consent moments
 * (save, update confirm, removal confirm) and the generation store's forget path already do. Reads are safe
 * without it because every write is an atomic replacement.
 * </p>
 */
public final class ClientSelectionStore {
	private final Path path;

	public ClientSelectionStore(Path path) {
		this.path = Objects.requireNonNull(path);
	}

	public Optional<SelectionIntent> get(String modpackId) {
		ModpackId.requireValid(modpackId);
		SelectionJsons.ClientSelectionStoreFields fields = read();
		SelectionJsons.ClientSelectionStoreFields.ModpackSelection selection = fields.selections.get(modpackId);
		return selection == null ? Optional.empty() : Optional.of(intent(selection));
	}

	/** Records the desire verbatim: the store is the player's declared choice, and the last consent wins. */
	public void put(String modpackId, SelectionIntent target) throws IOException {
		ModpackId.requireValid(modpackId);
		Objects.requireNonNull(target);
		SelectionJsons.ClientSelectionStoreFields fields = read();
		fields.selections.put(modpackId, new SelectionJsons.ClientSelectionStoreFields.ModpackSelection(new LinkedHashSet<>(target.requestedGroups()),
				new LinkedHashSet<>(target.requestedCategories()), new LinkedHashSet<>(target.excludedGroups()), target.platform() == null ? null : target.platform().id()));
		fields.selections = new LinkedHashMap<>(new TreeMap<>(fields.selections));
		ConfigTools.writeAtomic(path, fields);
	}

	/** Clears the desire: the player asked for the pack to go. */
	public void remove(String modpackId) throws IOException {
		ModpackId.requireValid(modpackId);
		SelectionJsons.ClientSelectionStoreFields fields = read();
		if (fields.selections.remove(modpackId) == null) return;
		fields.selections = new LinkedHashMap<>(new TreeMap<>(fields.selections));
		ConfigTools.writeAtomic(path, fields);
	}

	private static SelectionIntent intent(SelectionJsons.ClientSelectionStoreFields.ModpackSelection selection) {
		return new SelectionIntent(selection.requestedGroups, selection.requestedCategories, selection.excludedGroups, platform(selection.platform));
	}

	private static ClientPlatform platform(String platform) {
		// Strict on purpose: a stored platform this build cannot parse is corrupt durable state, so the
		// read's set-aside path handles it loudly instead of silently re-resolving under another platform.
		return platform == null ? null : ClientPlatform.parse(platform);
	}

	private SelectionJsons.ClientSelectionStoreFields read() {
		try {
			SelectionJsons.ClientSelectionStoreFields fields = ConfigTools.readState(path, SelectionJsons.ClientSelectionStoreFields.class, "Client group selection", ClientSelectionStore::validated)
					.orElseGet(SelectionJsons.ClientSelectionStoreFields::new);
			if (fields.selections == null) fields.selections = new LinkedHashMap<>();
			return fields;
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to read configuration " + path.toAbsolutePath().normalize(), e);
		}
	}

	/** The document's completeness contract: unusable content is set aside as evidence and reads as no selection. */
	private static SelectionJsons.ClientSelectionStoreFields validated(SelectionJsons.ClientSelectionStoreFields fields) {
		if (fields.DO_NOT_CHANGE_IT != 1) throw new IllegalArgumentException("Client group selection file version " + fields.DO_NOT_CHANGE_IT + " is not supported");
		if (fields.selections == null) fields.selections = new LinkedHashMap<>();
		for (SelectionJsons.ClientSelectionStoreFields.ModpackSelection selection : fields.selections.values()) platform(selection.platform);
		return fields;
	}
}
