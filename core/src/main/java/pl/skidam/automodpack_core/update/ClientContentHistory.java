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
import pl.skidam.automodpack_core.modpack.generation.GenerationMetadata;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;

public final class ClientContentHistory {
	public static final int SCHEMA_VERSION = 1;

	private ClientContentHistory() {}

	public record Entry(String stateDigest, String modpackName, String patchNotes, String recordedAt, Set<String> selectedTags, Set<String> selectedGroups,
			String fileSummary) {
		public Entry {
			if (stateDigest == null || !stateDigest.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid history state digest");
			modpackName = modpackName == null ? "" : modpackName;
			patchNotes = GenerationMetadata.validateNotes(patchNotes == null ? "" : patchNotes);
			recordedAt = requireInstant(recordedAt);
			selectedTags = Set.copyOf(new TreeSet<>(selectedTags == null ? Set.of() : selectedTags));
			selectedGroups = Set.copyOf(new TreeSet<>(selectedGroups == null ? Set.of() : selectedGroups));
			fileSummary = fileSummary == null ? "" : fileSummary;
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
				entry = new Entry(field.stateDigest, field.modpackName, field.patchNotes, field.recordedAt, field.selectedTags, field.selectedGroups, field.fileSummary);
			} catch (RuntimeException e) {
				throw new IOException("Client content history entry is invalid", e);
			}
			if (!states.add(entry.stateDigest())) throw new IOException("Client content history contains repeated content states");
			entries.add(entry);
		}
		return new History(fields.modpackId, entries);
	}

	public static void record(Path historyFile, Jsons.ModpackContentFields target, ResolvedSelection selection, String patchNotes) throws IOException {
		Objects.requireNonNull(historyFile, "historyFile");
		Objects.requireNonNull(target, "target");
		ModpackId.requireValid(target.modpackId);
		if (target.stateDigest == null || !target.stateDigest.matches("[0-9a-f]{40}")) throw new IOException("Target state digest is invalid");
		History history = read(historyFile);
		if (!history.modpackId().isEmpty() && !history.modpackId().equals(target.modpackId)) throw new IOException("Client content history modpack ID changed");
		Set<String> selectedTags = selection == null ? Set.of() : selection.intent().requestedTags();
		Set<String> selectedGroups = selection == null ? target.selectedGroups : selection.selectedGroups();
		Entry next = new Entry(target.stateDigest, target.modpackName, patchNotes, Instant.now().toString(), selectedTags, selectedGroups, fileSummary(target));
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
			field.stateDigest = entry.stateDigest();
			field.modpackName = entry.modpackName();
			field.patchNotes = entry.patchNotes();
			field.recordedAt = entry.recordedAt();
			field.selectedTags = entry.selectedTags();
			field.selectedGroups = entry.selectedGroups();
			field.fileSummary = entry.fileSummary();
			fields.entries.add(field);
		}
		ConfigTools.writeAtomic(historyFile, fields);
	}

	private static String fileSummary(Jsons.ModpackContentFields target) throws IOException {
		if (target.list == null) throw new IOException("Target file list is missing");
		long totalBytes = 0;
		for (var item : target.list) {
			if (item == null || item.file == null || item.file.isBlank()) throw new IOException("Target file entry is incomplete");
			try {
				long size = Long.parseLong(item.size);
				if (size < 0) throw new NumberFormatException("negative size");
				totalBytes = Math.addExact(totalBytes, size);
			} catch (NumberFormatException | ArithmeticException e) {
				throw new IOException("Target file size is invalid", e);
			}
		}
		return target.list.size() + " files, " + formatBytes(totalBytes);
	}

	private static String formatBytes(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
		if (bytes < 1024L * 1024L * 1024L) return (bytes / (1024 * 1024)) + " MiB";
		return (bytes / (1024L * 1024L * 1024L)) + " GiB";
	}

	private static String requireInstant(String value) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("History timestamp is missing");
		Instant parsed = Instant.parse(value);
		if (!parsed.toString().equals(value)) throw new IllegalArgumentException("History timestamp is not canonical");
		return value;
	}
}
