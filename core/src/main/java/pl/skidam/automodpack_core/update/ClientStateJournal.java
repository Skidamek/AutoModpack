package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.utils.HashUtils.isCanonicalSha1;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.JsonLines;

/**
 * The instance timeline journal: one small line per snapshot ({@code seq, parent, treeHash, event}). Tree documents
 * live beside it; file bytes stay in shared CAS.
 */
public final class ClientStateJournal {
	private static final Gson COMPACT = ConfigTools.strictEnums(new GsonBuilder().disableHtmlEscaping()).create();
	public static final long NO_PARENT = 0;

	public enum Kind {
		LIVE, INSTALL, UPDATE, ROLLBACK, RESTORE, REMOVAL, DEACTIVATION, REPAIR, FILE_RESTORE;

		/** Blank means the caller should derive the kind. An unknown persisted name fails loudly. */
		public static Kind parseDeclared(String name) {
			if (name == null || name.isBlank()) return null;
			try {
				return valueOf(name);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Unknown snapshot kind '" + name + "'", e);
			}
		}
	}

	public record Snapshot(long seq, long parentSeq, String treeSha1, Kind kind, String modpackId, String transactionId, Instant createdAt) {
		public Snapshot {
			if (seq < 1) throw new IllegalArgumentException("Snapshot sequence must be positive");
			if (parentSeq < NO_PARENT) throw new IllegalArgumentException("Invalid parent snapshot");
			if (!isCanonicalSha1(treeSha1)) throw new IllegalArgumentException("Invalid snapshot tree hash");
			Objects.requireNonNull(kind, "kind");
			modpackId = modpackId == null || modpackId.isBlank() ? "" : ModpackId.requireValid(modpackId);
			if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("Snapshot is missing its transaction id");
			createdAt = Objects.requireNonNull(createdAt, "createdAt");
		}

		ClientStorageJsons.SnapshotFields toFields() {
			ClientStorageJsons.SnapshotFields fields = new ClientStorageJsons.SnapshotFields();
			fields.seq = seq;
			fields.parentSeq = parentSeq;
			fields.treeSha1 = treeSha1;
			fields.kind = kind.name();
			fields.modpackId = modpackId;
			fields.transactionId = transactionId;
			fields.createdAt = createdAt.toString();
			return fields;
		}

		static Snapshot fromFields(ClientStorageJsons.SnapshotFields fields) {
			if (fields == null) throw new IllegalArgumentException("Snapshot is missing");
			Instant createdAt;
			try {
				createdAt = Instant.parse(fields.createdAt);
			} catch (DateTimeParseException | NullPointerException e) {
				throw new IllegalArgumentException("Invalid snapshot timestamp", e);
			}
			Kind kind;
			try {
				kind = Kind.valueOf(fields.kind);
			} catch (IllegalArgumentException | NullPointerException e) {
				throw new IllegalArgumentException("Unknown snapshot kind '" + fields.kind + "'", e);
			}
			return new Snapshot(fields.seq, fields.parentSeq, fields.treeSha1, kind, fields.modpackId, fields.transactionId, createdAt);
		}
	}

	private final Path file;
	private List<Snapshot> entries;

	private ClientStateJournal(Path file, List<Snapshot> entries) {
		this.file = file;
		this.entries = entries;
	}

	public static ClientStateJournal open(ClientStorage storage) throws IOException {
		return open(storage.stateHistoryJournalFile());
	}

	public static ClientStateJournal open(Path file) throws IOException {
		try {
			return new ClientStateJournal(file, JsonLines.read(file, "instance timeline", line -> Snapshot.fromFields(COMPACT.fromJson(line, ClientStorageJsons.SnapshotFields.class)), true));
		} catch (JsonLines.UnusableContentException e) {
			LOGGER.error("The instance timeline is corrupt and was moved aside; earlier snapshots are no longer restorable from it: {}", file, e);
			DurableFiles.setAside(file, "Instance timeline", e);
			return new ClientStateJournal(file, List.of());
		}
	}

	public List<Snapshot> entries() {
		return entries;
	}

	public Snapshot head() {
		if (entries.isEmpty()) throw new IllegalStateException("The instance timeline is empty");
		return entries.get(entries.size() - 1);
	}

	public Snapshot require(long seq) throws IOException {
		return entries.stream().filter(entry -> entry.seq() == seq).findFirst().orElseThrow(() -> new IOException("No instance snapshot " + seq));
	}

	public synchronized Snapshot append(Snapshot entry) throws IOException {
		Objects.requireNonNull(entry, "entry");
		long expected = entries.isEmpty() ? 1 : entries.get(entries.size() - 1).seq() + 1;
		if (entry.seq() != expected) throw new IOException("Snapshot " + entry.seq() + " does not follow " + (expected - 1));
		long expectedParent = entries.isEmpty() ? NO_PARENT : entries.get(entries.size() - 1).seq();
		if (entry.parentSeq() != expectedParent) throw new IOException("Snapshot " + entry.seq() + " parent does not match the timeline head");
		String line = COMPACT.toJson(entry.toFields());
		Files.createDirectories(file.getParent());
		Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
		List<Snapshot> updated = new ArrayList<>(entries);
		updated.add(entry);
		entries = List.copyOf(updated);
		return entry;
	}

	public synchronized Snapshot append(String treeSha1, Kind kind, String modpackId, String transactionId) throws IOException {
		long seq = entries.isEmpty() ? 1 : entries.get(entries.size() - 1).seq() + 1;
		long parent = entries.isEmpty() ? NO_PARENT : entries.get(entries.size() - 1).seq();
		return append(new Snapshot(seq, parent, treeSha1, kind, modpackId, transactionId, Instant.now()));
	}

	/** Rewrites the journal to {@code remaining}, used by forget-prefix. The first remaining snapshot's parent becomes none. */
	public synchronized void replaceAll(List<Snapshot> remaining) throws IOException {
		if (remaining.isEmpty()) throw new IOException("The instance timeline cannot be empty after forget");
		List<Snapshot> rewritten = new ArrayList<>();
		for (int index = 0; index < remaining.size(); index++) {
			Snapshot entry = remaining.get(index);
			long parent = index == 0 ? NO_PARENT : remaining.get(index - 1).seq();
			rewritten.add(new Snapshot(entry.seq(), parent, entry.treeSha1(), entry.kind(), entry.modpackId(), entry.transactionId(), entry.createdAt()));
		}
		StringBuilder text = new StringBuilder();
		for (Snapshot entry : rewritten) text.append(COMPACT.toJson(entry.toFields())).append('\n');
		Files.createDirectories(file.getParent());
		DurableFiles.writeAtomic(file, text.toString().getBytes(StandardCharsets.UTF_8));
		entries = List.copyOf(rewritten);
	}
}
