package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.JsonLines;

/**
 * The one file of a pending client update: line 1 is the compact transaction document, every later line is one phase
 * record appended at a boundary. The journal owns the path, the format, and the phase, so a phase has exactly one
 * carrier and a reader never reconciles a document against a hand-split tail. A record this build cannot read - an
 * unknown phase name, an unknown record schema, a record of another transaction - is unusable content that is set
 * aside with a loud log, because ignoring it would silently walk the phase backwards.
 */
public final class UpdateJournal {
	private static final String DESCRIPTION = "Persisted update transaction";
	/** The phase record format, independent of the journal schema so a record is never guessed at. */
	private static final int PHASE_RECORD_SCHEMA_VERSION = 1;
	/** The receipt of an update only a fresh review can re-approve, next to the journal it was moved from. */
	private static final String QUARANTINE_PREFIX = "update-transaction.stuck-";

	private final Path file;

	public UpdateJournal(ClientStorage storage) {
		this(storage.transactionFile());
	}

	public UpdateJournal(Path file) {
		this.file = Objects.requireNonNull(file, "file");
	}

	public Path file() {
		return file;
	}

	/**
	 * One observation of the journal: the transaction and the phase its records prove. Every reader derives its
	 * started/published answers from here, so nothing restates a phase the file does not say.
	 */
	public record Cursor(UpdateTransaction transaction, UpdateTransaction.Phase phase) {
		public Cursor {
			Objects.requireNonNull(transaction, "transaction");
			Objects.requireNonNull(phase, "phase");
		}

		/** Whether the apply may already have written something: PREPARING is persisted before its first write and never walked back. */
		public boolean started() {
			return phase.ordinal() >= UpdateTransaction.Phase.PREPARING.ordinal();
		}

		/** Whether the projection convergence may have begun, which proves every live operation landed: PROJECTED is persisted before its first write. */
		public boolean published() {
			return phase.ordinal() >= UpdateTransaction.Phase.PROJECTED.ordinal();
		}
	}

	/** The canonical reader: unusable content is set aside as evidence and reads as no pending update, while physical IO propagates. */
	public Cursor read() throws IOException {
		try {
			requireRegularOrAbsent();
			return observed(JsonLines.read(file, DESCRIPTION, parser(), true));
		} catch (JsonLines.UnusableContentException unusable) {
			DurableFiles.setAside(file, DESCRIPTION, unusable);
			return null;
		} catch (NoSuchFileException retired) {
			return null;
		}
	}

	/**
	 * Reads for a planning observer: corrupt content is set aside as evidence exactly as {@link #read()} does, while a
	 * torn final line is ignored in memory and never repaired, because an observer may race a helper's locked append.
	 */
	public Cursor observe() throws IOException {
		try {
			requireRegularOrAbsent();
			return observed(JsonLines.readIgnoringTornTail(file, DESCRIPTION, parser()));
		} catch (JsonLines.UnusableContentException unusable) {
			DurableFiles.setAside(file, DESCRIPTION, unusable);
			return null;
		} catch (NoSuchFileException retired) {
			return null;
		}
	}

	/** Reads without repairing or asiding: a journal a finishing commit retires during the read is no pending update, and unusable content stays where recovery will find it. */
	public Cursor readWithoutRepair() throws IOException {
		try {
			requireRegularOrAbsent();
			return observed(JsonLines.readIgnoringTornTail(file, DESCRIPTION, parser()));
		} catch (NoSuchFileException retired) {
			return null;
		}
	}

	/** Writes the fresh journal: the plan document as one line, with no record after it yet. */
	public void write(UpdateTransaction transaction) throws IOException {
		// The terminator is part of the format: without it the first appended record would fuse onto the document line.
		DurableFiles.writeAtomic(file, (ConfigTools.COMPACT.toJson(transaction) + "\n").getBytes(StandardCharsets.UTF_8));
	}

	/** Appends one durable phase boundary. The document is not rewritten, so advancing a phase costs a record instead of the whole plan. */
	public void advance(UpdateTransaction transaction, UpdateTransaction.Phase phase) throws IOException {
		JsonLines.appendLine(file, ConfigTools.COMPACT.toJson(new PhaseRecord(transaction.transactionId, phase)));
	}

	/** Rewrites the journal as its document plus the terminal phase record in one atomic replace, so a crash can never keep one without the other. */
	public void writeResult(UpdateTransaction transaction, UpdateTransaction.Phase phase) throws IOException {
		DurableFiles.writeAtomic(file, (ConfigTools.COMPACT.toJson(transaction) + "\n" + ConfigTools.COMPACT.toJson(new PhaseRecord(transaction.transactionId, phase)) + "\n")
				.getBytes(StandardCharsets.UTF_8));
	}

	public void retire() throws IOException {
		Files.deleteIfExists(file);
	}

	/** Moves the journal aside as the receipt of an update a fresh review must replace; null when none is left to move. */
	public Path quarantine() throws IOException {
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
		Path quarantined = file.resolveSibling(QUARANTINE_PREFIX + UUID.randomUUID() + ".json");
		Files.move(file, quarantined, StandardCopyOption.REPLACE_EXISTING);
		return quarantined;
	}

	/**
	 * One attribute read decides both questions, so a journal a finishing commit retires between two calls is absent
	 * rather than unusable: only a path that still exists and is not a regular file is corruption.
	 */
	private void requireRegularOrAbsent() throws JsonLines.UnusableContentException {
		BasicFileAttributes attributes;
		try {
			attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (NoSuchFileException retired) {
			return;
		} catch (IOException unreadable) {
			throw new JsonLines.UnusableContentException(0, DESCRIPTION + " cannot be inspected: " + file, unreadable);
		}
		if (!attributes.isRegularFile()) throw new JsonLines.UnusableContentException(0, DESCRIPTION + " is not a regular file: " + file, null);
	}

	private Cursor observed(List<Object> lines) throws JsonLines.UnusableContentException {
		if (lines.isEmpty()) {
			if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
			throw new JsonLines.UnusableContentException(1, DESCRIPTION + " has no document: " + file, null);
		}
		UpdateTransaction transaction = (UpdateTransaction) lines.get(0);
		UpdateTransaction.Phase phase = UpdateTransaction.Phase.PLANNED;
		for (int index = 1; index < lines.size(); index++) {
			PhaseRecord record = (PhaseRecord) lines.get(index);
			if (!Objects.equals(record.transactionId, transaction.transactionId))
				throw new JsonLines.UnusableContentException(index + 1, "Phase record " + (index + 1) + " names transaction " + record.transactionId + " but the document is " + transaction.transactionId, null);
			phase = phaseOf(record, index + 1);
		}
		return new Cursor(transaction, phase);
	}

	/** A phase name this build does not know is unusable content, never a record to skip: skipping it would read the phase from an earlier record. */
	private static UpdateTransaction.Phase phaseOf(PhaseRecord record, int lineNumber) throws JsonLines.UnusableContentException {
		try {
			return UpdateTransaction.Phase.valueOf(record.phase);
		} catch (IllegalArgumentException | NullPointerException unknown) {
			throw new JsonLines.UnusableContentException(lineNumber, "Phase record " + lineNumber + " names an unknown phase '" + record.phase + "'", unknown);
		}
	}

	private static Function<String, Object> parser() {
		int[] position = {0};
		return line -> entry(line, position[0]++);
	}

	private static Object entry(String line, int position) {
		return position == 0 ? document(line) : phaseRecord(line);
	}

	/** The document is published by one atomic replace, so it is never torn: any failure in it is unusable content rather than a crash to ignore. */
	private static UpdateTransaction document(String line) {
		try {
			return UpdateTransaction.validated(ConfigTools.COMPACT.fromJson(line, UpdateTransaction.class));
		} catch (RuntimeException unusable) {
			throw new IllegalArgumentException("The update transaction document is unusable", unusable);
		}
	}

	/**
	 * A record breaks the entry contract on an unknown schema or a missing transaction name, which is corrupt anywhere.
	 * Malformed JSON deliberately stays a {@link com.google.gson.JsonParseException} so the line format's torn-tail rule
	 * still applies to the final line; the phase name is resolved afterwards, in {@link #phaseOf}.
	 */
	private static PhaseRecord phaseRecord(String line) {
		PhaseRecord record = ConfigTools.COMPACT.fromJson(line, PhaseRecord.class);
		if (record == null || record.schemaVersion != PHASE_RECORD_SCHEMA_VERSION) throw new IllegalArgumentException("An update transaction phase record names no known record schema: " + line);
		if (record.transactionId == null || record.transactionId.isBlank()) throw new IllegalArgumentException("An update transaction phase record names no transaction: " + line);
		return record;
	}

	/** One append-only phase record. A class tree, not a record: the game's Gson cannot deserialize records. */
	static final class PhaseRecord {
		public int schemaVersion = PHASE_RECORD_SCHEMA_VERSION;
		public String transactionId;
		public String phase;

		public PhaseRecord() {}

		PhaseRecord(String transactionId, UpdateTransaction.Phase phase) {
			this.transactionId = transactionId;
			this.phase = phase.name();
		}
	}
}
