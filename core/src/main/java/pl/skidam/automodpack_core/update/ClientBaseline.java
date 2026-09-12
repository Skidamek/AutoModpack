package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The validated pre-update client state baseline for one modpack, owning the persisted baseline.json schema. */
public record ClientBaseline(String modpackId, List<Entry> entries) {
	private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::logicalPath);

	public ClientBaseline {
		modpackId = ModpackId.requireValid(modpackId);
		List<Entry> sorted = new ArrayList<>(Objects.requireNonNull(entries, "client baseline entries"));
		sorted.sort(ENTRY_ORDER);
		for (int i = 1; i < sorted.size(); i++)
			if (sorted.get(i - 1).logicalPath().equals(sorted.get(i).logicalPath()))
				throw new IllegalArgumentException("Client baseline contains duplicate paths");
		entries = List.copyOf(sorted);
	}

	/**
	 * Reads the modpack's persisted baseline, returning an empty baseline when none was persisted yet; a file that
	 * fails to parse or answers to another pack is set aside as evidence and reads as empty, and the next update
	 * rebuilds the baseline.
	 */
	public static ClientBaseline read(ClientStorage storage, String modpackId) throws IOException {
		Path path = storage.baselineFile(modpackId);
		return ConfigTools.readState(path, ClientStorageJsons.ClientBaselineFields.class, "Client baseline", fields -> answeringTo(modpackId, path, fields))
				.orElse(new ClientBaseline(modpackId, List.of()));
	}

	private static ClientBaseline answeringTo(String modpackId, Path path, ClientStorageJsons.ClientBaselineFields fields) {
		ClientBaseline baseline = fromFields(fields);
		if (!baseline.modpackId().equals(ModpackId.requireValid(modpackId))) throw new IllegalArgumentException("Client baseline does not answer to its path: " + path);
		return baseline;
	}

	public void write(ClientStorage storage) throws IOException {
		Path path = storage.baselineFile(modpackId);
		Files.createDirectories(path.getParent());
		ConfigTools.writeAtomic(path, toFields());
	}

	public Map<String, Entry> entriesByPath() {
		Map<String, Entry> byPath = new TreeMap<>();
		for (Entry entry : entries) byPath.put(entry.logicalPath(), entry);
		return Map.copyOf(byPath);
	}

	public static ClientBaseline fromFields(ClientStorageJsons.ClientBaselineFields fields) {
		if (fields == null || fields.schemaVersion != 1 || fields.modpackId == null || fields.entries == null) throw new IllegalArgumentException("Client baseline fields are incomplete");
		List<Entry> entries = new ArrayList<>();
		for (ClientStorageJsons.ClientBaselineFields.EntryFields value : fields.entries) {
			if (value == null) throw new IllegalArgumentException("Client baseline contains a null entry");
			entries.add(new Entry(value.logicalPath, value.objectHash, value.size, value.absent, value.baselineGenerationId));
		}
		return new ClientBaseline(fields.modpackId, entries);
	}

	public ClientStorageJsons.ClientBaselineFields toFields() {
		ClientStorageJsons.ClientBaselineFields fields = new ClientStorageJsons.ClientBaselineFields();
		fields.modpackId = modpackId;
		fields.entries = entries.stream().map(entry -> {
			ClientStorageJsons.ClientBaselineFields.EntryFields value = new ClientStorageJsons.ClientBaselineFields.EntryFields();
			value.logicalPath = entry.logicalPath();
			value.objectHash = entry.objectHash();
			value.size = entry.size();
			value.absent = entry.absent();
			value.baselineGenerationId = entry.baselineGenerationId();
			return value;
		}).toList();
		return fields;
	}

	public record Entry(String logicalPath, String objectHash, long size, boolean absent, String baselineGenerationId) {
		public Entry {
			logicalPath = LogicalPath.requireCanonical(logicalPath);
			if (absent) {
				if (objectHash == null || !objectHash.isEmpty() || size != -1) throw new IllegalArgumentException("Absent client baseline entry has content: " + logicalPath);
				objectHash = "";
				baselineGenerationId = baselineGenerationId == null ? "" : baselineGenerationId;
			} else {
				if (objectHash == null || !HashUtils.isSha1(objectHash) || size < 0) throw new IllegalArgumentException("Client baseline entry metadata is invalid: " + logicalPath);
				objectHash = HashUtils.normalizeSha1(objectHash);
			}
		}
	}
}
