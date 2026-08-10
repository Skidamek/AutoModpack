package pl.skidam.automodpack_core.modpack.group;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.SelectionJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;

public final class ClientSelectionStore {
	private static final Object LOCK = new Object();
	private final Path path;

	public ClientSelectionStore(Path path) {
		this.path = Objects.requireNonNull(path);
	}

	public Optional<SelectionIntent> get(String modpackId) {
		synchronized (LOCK) {
			ModpackId.requireValid(modpackId);
			SelectionJsons.ClientSelectionStoreFields fields = read();
			SelectionJsons.ClientSelectionStoreFields.ModpackSelection selection = fields.selections.get(modpackId);
			return selection == null ? Optional.empty() : Optional.of(intent(selection));
		}
	}

	public void compareAndSet(String modpackId, SelectionIntent expected, SelectionIntent target) throws IOException {
		synchronized (LOCK) {
			ModpackId.requireValid(modpackId);
			Objects.requireNonNull(target);
			SelectionJsons.ClientSelectionStoreFields fields = read();
			SelectionJsons.ClientSelectionStoreFields.ModpackSelection currentFields = fields.selections.get(modpackId);
			SelectionIntent current = currentFields == null ? null : intent(currentFields);
			if (!Objects.equals(current, expected) && !Objects.equals(current, target))
				throw new IOException("Group selection changed after planning for modpack " + modpackId);
			fields.selections.put(modpackId, new SelectionJsons.ClientSelectionStoreFields.ModpackSelection(new LinkedHashSet<>(target.requestedGroups()),
					new LinkedHashSet<>(target.requestedTags()), new LinkedHashSet<>(target.excludedGroups())));
			fields.selections = new LinkedHashMap<>(new TreeMap<>(fields.selections));
			ConfigTools.writeAtomic(path, fields);
		}
	}

	public void remove(String modpackId, SelectionIntent expected) throws IOException {
		synchronized (LOCK) {
			ModpackId.requireValid(modpackId);
			SelectionJsons.ClientSelectionStoreFields fields = read();
			SelectionJsons.ClientSelectionStoreFields.ModpackSelection currentFields = fields.selections.get(modpackId);
			SelectionIntent current = currentFields == null ? null : intent(currentFields);
			if (current != null && !Objects.equals(current, expected))
				throw new IOException("Group selection changed after removal planning for modpack " + modpackId);
			if (current == null) return;
			fields.selections.remove(modpackId);
			fields.selections = new LinkedHashMap<>(new TreeMap<>(fields.selections));
			ConfigTools.writeAtomic(path, fields);
		}
	}

	private static SelectionIntent intent(SelectionJsons.ClientSelectionStoreFields.ModpackSelection selection) {
		return new SelectionIntent(selection.requestedGroups, selection.requestedTags, selection.excludedGroups);
	}

	private SelectionJsons.ClientSelectionStoreFields read() {
		SelectionJsons.ClientSelectionStoreFields fields = ConfigTools.read(path, SelectionJsons.ClientSelectionStoreFields.class)
				.orElseGet(SelectionJsons.ClientSelectionStoreFields::new);
		if (fields.selections == null) fields.selections = new LinkedHashMap<>();
		return fields;
	}
}
