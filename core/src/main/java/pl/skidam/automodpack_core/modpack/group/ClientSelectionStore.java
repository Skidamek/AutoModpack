package pl.skidam.automodpack_core.modpack.group;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.SelectionJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;

/**
 * The persisted per-modpack group selection: the player's sparse override ({@code enabled}/{@code disabled}/{@code platform}),
 * written at every consent moment.
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
		fields.selections.put(modpackId, new SelectionJsons.ClientSelectionStoreFields.ModpackSelection(
				new LinkedHashSet<>(target.enabled()), new LinkedHashSet<>(target.disabled()),
				target.platform() == null ? null : target.platform().id()));
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
		return new SelectionIntent(selection.enabled, selection.disabled, platform(selection.platform));
	}

	private static ClientPlatform platform(String platform) {
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
		if (fields.DO_NOT_CHANGE_IT != 2) throw new IllegalArgumentException("Client group selection file version " + fields.DO_NOT_CHANGE_IT + " is not supported");
		if (fields.selections == null) fields.selections = new LinkedHashMap<>();
		for (var entry : fields.selections.entrySet()) {
			ModpackId.requireValid(entry.getKey());
			if (entry.getValue() == null) throw new IllegalArgumentException("Selection entry for " + entry.getKey() + " is null");
			intent(entry.getValue());
		}
		return fields;
	}
}
