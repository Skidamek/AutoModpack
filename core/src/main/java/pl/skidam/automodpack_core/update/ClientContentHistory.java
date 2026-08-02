package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;

public final class ClientContentHistory {
	public static final int SCHEMA_VERSION = 1;

	private ClientContentHistory() {}

	public record Entry(String generationId, String stateDigest, String modpackName, String recordedAt, Set<String> selectedGroups) {
		public Entry {
			if (generationId == null || !generationId.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid history generation ID");
			if (stateDigest == null || !stateDigest.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid history state digest");
			modpackName = modpackName == null ? "" : modpackName;
			recordedAt = requireInstant(recordedAt);
			selectedGroups = Set.copyOf(new TreeSet<>(selectedGroups == null ? Set.of() : selectedGroups));
		}
	}

	public record History(String modpackId, List<Entry> entries) {
		public History {
			if (modpackId == null) modpackId = "";
			if (!modpackId.isEmpty()) ModpackId.requireValid(modpackId);
			entries = List.copyOf(entries == null ? List.of() : entries);
		}
	}

	public static History read(Path historyFile) throws IOException {
		Objects.requireNonNull(historyFile, "historyFile");
		if (!Files.exists(historyFile, LinkOption.NOFOLLOW_LINKS)) return new History("", List.of());
		if (Files.isSymbolicLink(historyFile) || !Files.isRegularFile(historyFile, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Client content history is not a regular file");
		Jsons.ClientContentHistoryFields fields;
		try {
			fields = ConfigTools.read(historyFile, Jsons.ClientContentHistoryFields.class)
					.orElseThrow(() -> new IOException("Client content history is empty"));
		} catch (RuntimeException e) {
			throw new IOException("Client content history is invalid", e);
		}
		if (fields.schemaVersion != SCHEMA_VERSION || fields.entries == null) throw new IOException("Client content history identity is invalid");
		ModpackId.requireValid(fields.modpackId);
		List<Entry> entries = new ArrayList<>();
		Set<String> states = new TreeSet<>();
		for (var field : fields.entries) {
			if (field == null) throw new IOException("Client content history entry is incomplete");
			Entry entry;
			try {
				entry = new Entry(field.generationId, field.stateDigest, field.modpackName, field.recordedAt, field.selectedGroups);
			} catch (RuntimeException e) {
				throw new IOException("Client content history entry is invalid", e);
			}
			if (!states.add(entry.stateDigest())) throw new IOException("Client content history contains repeated content states");
			entries.add(entry);
		}
		return new History(fields.modpackId, entries);
	}

	public static void record(Path historyFile, Jsons.ModpackContentFields target) throws IOException {
		Objects.requireNonNull(target, "target");
		ModpackId.requireValid(target.modpackId);
		if (target.targetGenerationId == null || !target.targetGenerationId.matches("[0-9a-f]{40}")) throw new IOException("Target generation ID is invalid");
		if (target.stateDigest == null || !target.stateDigest.matches("[0-9a-f]{40}")) throw new IOException("Target state digest is invalid");
		History history = read(historyFile);
		if (!history.modpackId().isEmpty() && !history.modpackId().equals(target.modpackId)) throw new IOException("Client content history modpack ID changed");
		Entry next = new Entry(target.targetGenerationId, target.stateDigest, target.modpackName, Instant.now().toString(), target.selectedGroups);
		List<Entry> entries = new ArrayList<>(history.entries());
		int existing = -1;
		for (int index = 0; index < entries.size(); index++) if (entries.get(index).stateDigest().equals(next.stateDigest())) {
			existing = index;
			break;
		}
		if (existing >= 0) {
			entries.subList(existing + 1, entries.size()).clear();
			entries.set(existing, next);
		} else {
			entries.add(next);
		}
		Jsons.ClientContentHistoryFields fields = new Jsons.ClientContentHistoryFields();
		fields.modpackId = target.modpackId;
		fields.entries = new ArrayList<>();
		for (Entry entry : entries) {
			Jsons.ClientContentHistoryFields.EntryFields field = new Jsons.ClientContentHistoryFields.EntryFields();
			field.generationId = entry.generationId();
			field.stateDigest = entry.stateDigest();
			field.modpackName = entry.modpackName();
			field.recordedAt = entry.recordedAt();
			field.selectedGroups = entry.selectedGroups();
			fields.entries.add(field);
		}
		ConfigTools.writeAtomic(historyFile, fields);
	}

	private static String requireInstant(String value) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("History timestamp is missing");
		Instant parsed = Instant.parse(value);
		if (!parsed.toString().equals(value)) throw new IllegalArgumentException("History timestamp is not canonical");
		return value;
	}
}
