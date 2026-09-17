package pl.skidam.automodpack_core.update;

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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.update.UpdatePlan.BaselineCapture;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JsonLines;

/**
 * The client's instance-wide state history: one append-only entry per committed file-state mutation, each a complete
 * checkpoint of every tracked file plus the change list and before-state captures that produced it. Entries pin their
 * bytes in the object store, so every state on the timeline stays restorable until an explicit retention decision
 * removes entries; nothing collects them by itself.
 */
public final class ClientStateJournal {
	private static final Gson COMPACT = ConfigTools.strictEnums(new GsonBuilder().disableHtmlEscaping()).create();

	/** What kind of committed mutation produced a state. Repairs and single-file restores append entries once their flows land on this journal. */
	public enum Kind {
		INSTALL, UPDATE, ROLLBACK, DEACTIVATION, REMOVAL, REPAIR, FILE_RESTORE, RECOVERY_REVERT
	}

	/** One tracked file of a checkpoint manifest: present after the entry's mutation, pinned by its hash. */
	public record TrackedFile(Root root, String path, String sha1, long size) {
		public TrackedFile {
			Objects.requireNonNull(root, "root");
			path = LogicalPath.requireCanonical(path);
			if (!isCanonicalSha1(sha1)) throw new IllegalArgumentException("Invalid tracked file hash for " + path);
			if (size < 0) throw new IllegalArgumentException("Negative tracked file size for " + path);
		}
	}

	/**
	 * One file change of the entry's mutation; a null source hash means the path is new, a null target hash means it
	 * is gone. A present hash with {@link #UNKNOWN_SIZE} names bytes the entry vouches for without a measured size.
	 */
	public record Change(Root root, String path, String fromSha1, long fromSize, String toSha1, long toSize) {
		public static final long UNKNOWN_SIZE = -1;

		public Change {
			Objects.requireNonNull(root, "root");
			path = LogicalPath.requireCanonical(path);
			if (fromSize < UNKNOWN_SIZE || toSize < UNKNOWN_SIZE) throw new IllegalArgumentException("Negative change size for " + path);
			if (fromSha1 != null && !isCanonicalSha1(fromSha1)) throw new IllegalArgumentException("Invalid change source for " + path);
			if (fromSha1 == null && fromSize != 0) throw new IllegalArgumentException("An added path cannot carry a previous size: " + path);
			if (toSha1 != null && !isCanonicalSha1(toSha1)) throw new IllegalArgumentException("Invalid change target for " + path);
			if (toSha1 == null && toSize != 0) throw new IllegalArgumentException("A removed path cannot carry a size: " + path);
		}

		public JournalEntry.Change.Kind kind() {
			if (fromSha1 == null) return JournalEntry.Change.Kind.ADDED;
			if (toSha1 == null) return JournalEntry.Change.Kind.REMOVED;
			return JournalEntry.Change.Kind.CHANGED;
		}

		static Change install(Root root, String path, String existingHash, String hash, long size) {
			return new Change(root, path, existingHash, existingHash == null ? 0 : UNKNOWN_SIZE, hash, size);
		}

		static Change removal(Root root, String path, String existingHash) {
			return new Change(root, path, existingHash, UNKNOWN_SIZE, null, 0);
		}
	}

	/**
	 * The before-state of one touched path the mutation overwrote or cleared: the reversible residue of the player's
	 * own file, pinned by its hash so the state before the first entry stays reconstructable.
	 */
	public record Capture(Root root, String path, String sha1, long size, boolean absent) {
		public Capture {
			Objects.requireNonNull(root, "root");
			path = LogicalPath.requireCanonical(path);
			if (absent) {
				if (sha1 != null || size != 0) throw new IllegalArgumentException("An absent capture cannot carry content: " + path);
			} else {
				if (!isCanonicalSha1(sha1)) throw new IllegalArgumentException("Invalid capture hash for " + path);
				if (size < 0) throw new IllegalArgumentException("Negative capture size for " + path);
			}
		}
	}

	/** One immutable state checkpoint: the complete tracked manifest after one committed mutation. */
	public record StateEntry(long seq, String transactionId, Kind kind, String modpackId, String contentToken, Instant createdAt, long restoreOfSeq,
			List<TrackedFile> state, List<Change> changes, List<Capture> captures) {
		public static final long NO_RESTORE = -1;
		public static final Comparator<TrackedFile> STATE_ORDER = Comparator.comparing((TrackedFile file) -> file.root().ordinal()).thenComparing(TrackedFile::path);
		public static final Comparator<Change> CHANGE_ORDER = Comparator.comparing((Change change) -> change.root().ordinal()).thenComparing(Change::path);
		public static final Comparator<Capture> CAPTURE_ORDER = Comparator.comparing((Capture capture) -> capture.root().ordinal()).thenComparing(Capture::path);

		public StateEntry {
			if (seq < 1) throw new IllegalArgumentException("State sequence must be positive");
			if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("State entry is missing its transaction id");
			Objects.requireNonNull(kind, "kind");
			modpackId = ModpackId.requireValid(modpackId);
			if (contentToken != null && !isCanonicalSha1(contentToken)) throw new IllegalArgumentException("Invalid state content token for " + modpackId);
			createdAt = Objects.requireNonNull(createdAt, "createdAt");
			if (restoreOfSeq < NO_RESTORE) throw new IllegalArgumentException("Invalid restore reference");
			state = canonicalList(state, STATE_ORDER, "state manifest", true);
			// A path replaced within one transaction is legitimately two changes; only the manifest and captures name each path once.
			changes = canonicalList(changes, CHANGE_ORDER, "change list", false);
			captures = canonicalList(captures, CAPTURE_ORDER, "capture list", true);
		}

		private static <T> List<T> canonicalList(List<T> values, Comparator<T> order, String description, boolean uniquePaths) {
			Objects.requireNonNull(values, description + " is missing");
			List<T> sorted = values.stream().sorted(order).toList();
			if (!sorted.equals(values)) throw new IllegalArgumentException("The " + description + " is not canonically sorted");
			if (uniquePaths && sorted.stream().distinct().count() != values.size()) throw new IllegalArgumentException("The " + description + " repeats a path");
			return List.copyOf(values);
		}

		public ClientStorageJsons.StateEntryFields toFields() {
			ClientStorageJsons.StateEntryFields fields = new ClientStorageJsons.StateEntryFields();
			fields.seq = seq;
			fields.transactionId = transactionId;
			fields.kind = kind.name();
			fields.modpackId = modpackId;
			fields.contentToken = contentToken == null ? "" : contentToken;
			fields.createdAt = createdAt.toString();
			fields.restoreOfSeq = restoreOfSeq;
			List<ClientStorageJsons.StateEntryFields.FileFields> stateFields = new ArrayList<>();
			for (TrackedFile file : state) {
				ClientStorageJsons.StateEntryFields.FileFields fileField = new ClientStorageJsons.StateEntryFields.FileFields();
				fileField.root = file.root().name();
				fileField.path = file.path();
				fileField.sha1 = file.sha1();
				fileField.size = file.size();
				stateFields.add(fileField);
			}
			fields.state = stateFields;
			List<ClientStorageJsons.StateEntryFields.ChangeFields> changeFields = new ArrayList<>();
			for (Change change : changes) {
				ClientStorageJsons.StateEntryFields.ChangeFields changeField = new ClientStorageJsons.StateEntryFields.ChangeFields();
				changeField.root = change.root().name();
				changeField.path = change.path();
				changeField.fromSha1 = change.fromSha1() == null ? "" : change.fromSha1();
				changeField.fromSize = change.fromSha1() == null ? 0 : change.fromSize();
				changeField.toSha1 = change.toSha1() == null ? "" : change.toSha1();
				changeField.toSize = change.toSha1() == null ? 0 : change.toSize();
				changeFields.add(changeField);
			}
			fields.changes = changeFields;
			List<ClientStorageJsons.StateEntryFields.CaptureFields> captureFields = new ArrayList<>();
			for (Capture capture : captures) {
				ClientStorageJsons.StateEntryFields.CaptureFields captureField = new ClientStorageJsons.StateEntryFields.CaptureFields();
				captureField.root = capture.root().name();
				captureField.path = capture.path();
				captureField.sha1 = capture.sha1() == null ? "" : capture.sha1();
				captureField.size = capture.absent() ? 0 : capture.size();
				captureField.absent = capture.absent();
				captureFields.add(captureField);
			}
			fields.captures = captureFields;
			return fields;
		}

		public static StateEntry fromFields(ClientStorageJsons.StateEntryFields fields) {
			if (fields == null) throw new IllegalArgumentException("State entry is missing");
			if (fields.seq < 1) throw new IllegalArgumentException("State sequence must be positive");
			if (fields.transactionId == null || fields.transactionId.isBlank()) throw new IllegalArgumentException("State entry is missing its transaction id");
			Kind kind = enumValue(Kind.class, fields.kind, "state entry kind");
			String modpackId = ModpackId.requireValid(fields.modpackId);
			String contentToken = fields.contentToken == null || fields.contentToken.isBlank() ? null : fields.contentToken;
			if (contentToken != null && !isCanonicalSha1(contentToken)) throw new IllegalArgumentException("Invalid state content token in state entry " + fields.seq);
			Instant createdAt;
			try {
				createdAt = Instant.parse(fields.createdAt);
			} catch (DateTimeParseException | NullPointerException e) {
				throw new IllegalArgumentException("Invalid creation timestamp in state entry " + fields.seq, e);
			}
			List<TrackedFile> state = new ArrayList<>();
			for (ClientStorageJsons.StateEntryFields.FileFields file : list(fields.state, "state manifest"))
				state.add(new TrackedFile(enumValue(Root.class, file.root, "tracked file root"), requirePath(file.path, "tracked file"), requireHash(file.sha1, file.path, "tracked file"), file.size));
			List<Change> changes = new ArrayList<>();
			for (ClientStorageJsons.StateEntryFields.ChangeFields change : list(fields.changes, "change list")) {
				String from = change.fromSha1 == null || change.fromSha1.isBlank() ? null : change.fromSha1;
				String to = change.toSha1 == null || change.toSha1.isBlank() ? null : change.toSha1;
				changes.add(new Change(enumValue(Root.class, change.root, "change root"), requirePath(change.path, "change"), from, from == null ? 0 : change.fromSize, to, to == null ? 0 : change.toSize));
			}
			List<Capture> captures = new ArrayList<>();
			for (ClientStorageJsons.StateEntryFields.CaptureFields capture : list(fields.captures, "capture list")) {
				String hash = capture.sha1 == null || capture.sha1.isBlank() ? null : capture.sha1;
				captures.add(new Capture(enumValue(Root.class, capture.root, "capture root"), requirePath(capture.path, "capture"), hash, hash == null ? 0 : capture.size, capture.absent));
			}
			return new StateEntry(fields.seq, fields.transactionId, kind, modpackId, contentToken, createdAt, fields.restoreOfSeq, state, changes, captures);
		}

		private static <E extends Enum<E>> E enumValue(Class<E> type, String name, String description) {
			try {
				return Enum.valueOf(type, name);
			} catch (IllegalArgumentException | NullPointerException e) {
				throw new IllegalArgumentException("Unknown " + description + " '" + name + "'", e);
			}
		}

		private static String requirePath(String path, String description) {
			if (path == null || path.isBlank()) throw new IllegalArgumentException(description + " is missing its path");
			return path;
		}

		private static String requireHash(String sha1, String path, String description) {
			if (sha1 == null || sha1.isBlank()) throw new IllegalArgumentException(description + " is missing its hash for " + path);
			return sha1;
		}

		private static <T> List<T> list(List<T> values, String description) {
			if (values == null) throw new IllegalArgumentException(description + " is missing");
			return values;
		}
	}

	private final Path file;
	private List<StateEntry> entries;

	private ClientStateJournal(Path file, List<StateEntry> entries) {
		this.file = file;
		this.entries = entries;
	}

	/** Opens the local durable state history, repairing away a torn final line left by a crash or power cut mid-append. */
	public static ClientStateJournal open(Path file) throws IOException {
		return new ClientStateJournal(file, JsonLines.read(file, "state history", line -> StateEntry.fromFields(COMPACT.fromJson(line, ClientStorageJsons.StateEntryFields.class)), true));
	}

	public List<StateEntry> entries() {
		return entries;
	}

	public StateEntry head() {
		if (entries.isEmpty()) throw new IllegalStateException("The state history is empty");
		return entries.get(entries.size() - 1);
	}

	public synchronized StateEntry append(StateEntry entry) throws IOException {
		Objects.requireNonNull(entry, "entry");
		long expected = entries.isEmpty() ? 1 : entries.get(entries.size() - 1).seq() + 1;
		if (entry.seq() != expected) throw new IOException("State entry " + entry.seq() + " does not follow " + (expected - 1));
		String line = COMPACT.toJson(entry.toFields());
		Files.createDirectories(file.getParent());
		Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
		List<StateEntry> updated = new ArrayList<>(entries);
		updated.add(entry);
		entries = List.copyOf(updated);
		return entry;
	}

	/**
	 * Appends the checkpoint of one committed transaction: the kind derives from the transaction's purpose and the
	 * pack's prior history, and nothing is recorded when the head entry already carries this transaction id - the
	 * idempotence that makes append-before-retire safe to replay after a crash between the two.
	 */
	public synchronized StateEntry appendTransaction(UpdateTransaction transaction) throws IOException {
		Objects.requireNonNull(transaction, "transaction");
		if (!entries.isEmpty() && entries.get(entries.size() - 1).transactionId().equals(transaction.transactionId)) return entries.get(entries.size() - 1);
		UpdatePlan plan = transaction.plan();
		long seq = entries.isEmpty() ? 1 : entries.get(entries.size() - 1).seq() + 1;
		return append(entryFor(transaction, plan, seq));
	}

	private StateEntry entryFor(UpdateTransaction transaction, UpdatePlan plan, long seq) {
		List<TrackedFile> state = new ArrayList<>();
		for (ProjectedFile projected : plan.projectedFinalState())
			if (projected.present()) state.add(new TrackedFile(projected.root(), projected.relativePath(), HashUtils.normalizeSha1(projected.expectedHash()), projected.expectedSize()));
		List<Change> changes = new ArrayList<>();
		for (Operation operation : plan.operations()) {
			String existingHash = operation.expectedExistingHash() == null ? null : HashUtils.normalizeSha1(operation.expectedExistingHash());
			boolean install = operation.operation() == OperationType.INSTALL_OBJECT;
			boolean remove = operation.operation() == OperationType.DELETE && existingHash != null;
			if (install) changes.add(Change.install(operation.root(), operation.relativePath(), existingHash, HashUtils.normalizeSha1(operation.expectedObjectHash()), operation.expectedSize()));
			if (remove) changes.add(Change.removal(operation.root(), operation.relativePath(), existingHash));
		}
		List<Capture> captures = new ArrayList<>();
		for (BaselineCapture capture : plan.baselineCaptures())
			captures.add(new Capture(capture.root(), capture.relativePath(), capture.absent() ? null : HashUtils.normalizeSha1(capture.expectedHash()), capture.absent() ? 0 : capture.expectedSize(), capture.absent()));
		// The planner's canonical order leads with the operation type; the entry's own canonical order is root-then-path.
		state.sort(StateEntry.STATE_ORDER);
		changes.sort(StateEntry.CHANGE_ORDER);
		captures.sort(StateEntry.CAPTURE_ORDER);
		String contentToken = plan.packTarget().contentToken() == null ? null : HashUtils.normalizeSha1(plan.packTarget().contentToken());
		// A flow that knows its own story better than the purpose mapping - the rollback - labels the entry itself.
		Kind kind = transaction.stateKind == null || transaction.stateKind.isBlank() ? kindFor(plan.modpackId(), transaction.purpose) : Kind.valueOf(transaction.stateKind);
		return new StateEntry(seq, transaction.transactionId, kind, plan.modpackId(), contentToken, Instant.now(), transaction.stateRestoreOfSeq, state, changes, captures);
	}

	private Kind kindFor(String modpackId, UpdateTransaction.Purpose purpose) {
		return switch (purpose) {
			case MODPACK_UPDATE -> entries.stream().anyMatch(entry -> entry.modpackId().equals(modpackId)) ? Kind.UPDATE : Kind.INSTALL;
			case MODPACK_DEACTIVATION -> Kind.DEACTIVATION;
			case MODPACK_REMOVAL -> Kind.REMOVAL;
		};
	}
}
