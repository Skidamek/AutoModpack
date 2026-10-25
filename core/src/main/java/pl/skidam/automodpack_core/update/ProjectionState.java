package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The one durable answer to "may a loader scan {@code active/} as it stands": the projection state document.
 *
 * <p>
 * Invariants, each enforced where named:
 * <ul>
 * <li><b>P1 (loadable).</b> While the document is absent or {@link Phase#SETTLED}, {@code active/} is one complete
 * projection: the one the last committed transaction converged it to (or, for a detached pack, what the player made of
 * it). Every other writer of {@code active/} (offline repair) only re-links files to that same projection, and says so
 * with {@link #touched}.</li>
 * <li><b>P2 (marked before mixed).</b> The commit persists {@link Phase#CONVERGING}, with the complete fallback tree
 * {@code active/} held at that moment, before its first projection write ({@code ProjectionConvergence.converge}), and
 * returns to {@link Phase#SETTLED} only after its transaction's COMMITTED marker, or after the fallback was restored.</li>
 * <li><b>P3 (gates consult it).</b> Every loader gate refuses {@code active/} while the phase is not settled or the
 * document cannot be read, for any reason: {@link ClientStorage#loadableProjectionModsDirectory},
 * {@code ClientLaunch.hasActiveProjection} and {@code ProjectionLoader}, through {@link #loadRefusal}.</li>
 * <li><b>P4 (a boot never runs on owed projection work).</b> Boot recovery and the publication sweep restore the
 * fallback from CAS whenever they leave a converging projection behind ({@code BootRecovery.settleProjection},
 * {@code PublicationSweep}).</li>
 * <li><b>P5 (revision and content).</b> Every write raises {@link #revision()}; a settled document also names the
 * digest of the tree it vouches for ({@link #contentDigest()}), so a loader that scanned before recovery can tell
 * whether it scanned the content this boot runs on, even across a restore to the same tree. Revisions only grow
 * and are not dense: a repair restarts them from the clock.</li>
 * <li><b>P6 (bad contents are never a reason to destroy).</b> Only content that parses to no valid document is
 * {@link UnreadableException}; a read that fails physically (permissions, I/O, a sharing violation) propagates as
 * the plain IOException it is, and no writer treats it as corruption. Unreadable contents are set aside as evidence
 * and repaired by {@code ProjectionConvergence}: with no journal nothing can be converging, so the document is
 * rewritten settled and {@code active/} is left alone; with a journal the journal rolls forward first.</li>
 * <li><b>P7 (missing means legacy, deliberately).</b> No document reads as settled, because every version before this
 * one left only complete projections behind and an upgrade must keep loading them. The one way a document of this
 * version goes missing - a filesystem without atomic rename replacing it - leaves its temporary behind, and that
 * reads as unreadable instead.</li>
 * <li><b>P8 (a persistent read failure heals).</b> A physical read failure a writer meets is retried within the read,
 * then reported by name and recorded; when it has persisted across a boot the document is set aside and the P6 repair
 * runs, so a broken ACL or bad sector never refuses the projection forever ({@link #readForWriter}). It is never a
 * held file.</li>
 * </ul>
 */
public final class ProjectionState {
	/**
	 * A writer cannot read the document for a physical reason (permissions, I/O). Never a held file: no game process
	 * holds this file, so {@link UpdateCommit#isLockFailure} stops here and the update fails with this message.
	 */
	public static final class UnavailableException extends IOException {
		UnavailableException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** The document exists but its content proves nothing: a torn or foreign write. Never a physical read failure. */
	public static final class UnreadableException extends IOException {
		UnreadableException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public enum Phase {
		SETTLED,
		CONVERGING
	}

	/** Where a converging fallback comes from: the rows recorded at the mark, or the committed generation (a repair). */
	public enum Fallback {
		RECORDED,
		COMMITTED
	}

	/** One file of a complete projection: the root-relative path and the CAS object it holds. */
	public record Row(String path, String sha1, long size) {
		public Row {
			path = LogicalPath.normalize(path);
			if (!HashUtils.isSha1(sha1)) throw new IllegalArgumentException("Invalid projection row hash for " + path);
			sha1 = HashUtils.normalizeSha1(sha1);
			if (size < 0) throw new IllegalArgumentException("Negative projection row size for " + path);
		}
	}

	/** The persisted shape; only this class reads or writes it. */
	static final class Fields {
		int version = 1;
		Phase phase;
		long revision;
		String transactionId;
		String committedToken;
		Fallback fallbackSource;
		String contentDigest;
		String targetDigest;
		List<RowFields> fallback;
	}

	static final class RowFields {
		String path;
		String sha1;
		long size;
	}

	private static final Gson GSON = new Gson();

	/** This process: a boot. A read failure recorded by another one has persisted across a restart (P8). */
	private static volatile String process = UUID.randomUUID().toString();

	/** Test seam: the next calls behave as a later boot. */
	static void assumeNextBoot() {
		process = UUID.randomUUID().toString();
	}

	static final String READ_FAILURE_FILE = "projection-state.read-failure.json";

	static final class ReadFailureFields {
		String process;
		String reason;
		long at;
	}

	/** The state of an instance without a document: older versions only ever left a complete projection behind. */
	private static final ProjectionState LEGACY = new ProjectionState(Phase.SETTLED, 0, null, null, Fallback.RECORDED, null, null, List.of());

	private final Phase phase;
	private final long revision;
	private final String transactionId;
	private final String committedToken;
	private final Fallback fallbackSource;
	private final String contentDigest;
	private final String targetDigest;
	private final List<Row> fallback;

	private ProjectionState(Phase phase, long revision, String transactionId, String committedToken, Fallback fallbackSource, String contentDigest, String targetDigest,
			List<Row> fallback) {
		this.phase = Objects.requireNonNull(phase, "phase");
		this.revision = revision;
		this.transactionId = transactionId;
		this.committedToken = committedToken;
		this.fallbackSource = fallbackSource == null ? Fallback.RECORDED : fallbackSource;
		this.contentDigest = contentDigest;
		this.targetDigest = targetDigest;
		this.fallback = List.copyOf(fallback);
	}

	public Phase phase() {
		return phase;
	}

	public boolean converging() {
		return phase == Phase.CONVERGING;
	}

	public long revision() {
		return revision;
	}

	/** The transaction that last wrote the document, or null. */
	public String transactionId() {
		return transactionId;
	}

	/**
	 * While converging: the content token the active state named when the fallback was taken. A different one at restore
	 * time means another writer - an older version after a downgrade - committed a generation since, so the fallback is
	 * that generation's projection instead ({@code ProjectionConvergence.restoreFallback}).
	 */
	public String committedToken() {
		return committedToken;
	}

	/** While converging: whether the fallback is the recorded rows or, after a repair, the committed generation. */
	public Fallback fallbackSource() {
		return fallbackSource;
	}

	/** While settled: the digest of the tree {@code active/} holds ({@link #digest}), or null when unknown (legacy, repair, offline repair). */
	public String contentDigest() {
		return contentDigest;
	}

	/** While converging: the digest of the tree the convergence ends in. */
	public String targetDigest() {
		return targetDigest;
	}

	/** While converging: the complete tree {@code active/} held when the convergence began. Empty when settled. */
	public List<Row> fallback() {
		return fallback;
	}

	/** Whether two observations vouch for the same loadable content: equal digests when both know one, else the same revision. */
	public boolean sameContentAs(ProjectionState other) {
		if (converging() || other.converging()) return false;
		if (contentDigest != null && other.contentDigest != null) return contentDigest.equals(other.contentDigest);
		return revision == other.revision;
	}

	public static Path file(Path gameDirectory) {
		return gameDirectory.resolve(StoragePaths.CLIENT_PROJECTION_STATE_FILE).normalize();
	}

	public static ProjectionState read(ClientStorage storage) throws IOException {
		return read(storage.projectionStateFile());
	}

	/**
	 * Reads without side effects, so a loader hook may call it. A physical failure propagates as itself; only content
	 * that is no valid document is an {@link UnreadableException} (P6). A missing document is legacy, unless the
	 * temporary of an interrupted non-atomic replace stands beside it (P7).
	 */
	public static ProjectionState read(Path file) throws IOException {
		byte[] bytes;
		try {
			bytes = readBytes(file);
		} catch (NoSuchFileException missing) {
			if (interruptedReplace(file)) throw new UnreadableException("Projection state is missing beside the temporary of an interrupted replace: " + file, missing);
			return LEGACY;
		}
		Fields fields;
		try {
			fields = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Fields.class);
		} catch (JsonParseException | IllegalStateException e) {
			throw new UnreadableException("Projection state is unreadable: " + file, e);
		}
		if (fields == null || fields.phase == null || fields.version != 1 || fields.revision < 0) throw new UnreadableException("Projection state is invalid: " + file, null);
		List<Row> rows = new ArrayList<>();
		try {
			if (fields.fallback != null) for (RowFields row : fields.fallback) rows.add(new Row(row.path, row.sha1, row.size));
		} catch (RuntimeException e) {
			throw new UnreadableException("Projection state fallback is invalid: " + file, e);
		}
		if (fields.phase == Phase.SETTLED && !rows.isEmpty()) throw new UnreadableException("Settled projection state carries a fallback: " + file, null);
		return new ProjectionState(fields.phase, fields.revision, fields.transactionId, fields.committedToken, fields.fallbackSource, fields.contentDigest, fields.targetDigest, rows);
	}

	/** A transient hold (a scanner, a sharing violation) is retried briefly; what persists propagates as itself. */
	private static byte[] readBytes(Path file) throws IOException {
		for (int attempt = 1;; attempt++) {
			try {
				return Files.readAllBytes(file);
			} catch (NoSuchFileException missing) {
				throw missing;
			} catch (IOException physical) {
				if (attempt >= READ_ATTEMPTS) throw physical;
				try {
					Thread.sleep(READ_RETRY_MILLIS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw physical;
				}
			}
		}
	}

	private static final int READ_ATTEMPTS = 3;
	private static final long READ_RETRY_MILLIS = 25;

	private static boolean interruptedReplace(Path file) throws IOException {
		Path parent = file.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return false;
		String prefix = "." + file.getFileName() + ".";
		try (Stream<Path> siblings = Files.list(parent)) {
			return siblings.map(path -> path.getFileName().toString()).anyMatch(name -> name.startsWith(prefix) && name.endsWith(DurableFiles.TEMPORARY_SUFFIX));
		}
	}

	/**
	 * Why a loader must not take {@code active/} as it stands right now, or null when it may (P3). Any document that
	 * cannot be read refuses: it cannot prove the tree complete.
	 */
	public static String loadRefusal(Path gameDirectory) {
		try {
			return refusal(read(file(gameDirectory)));
		} catch (IOException e) {
			return "the projection state cannot be read (" + e.getMessage() + ")";
		}
	}

	/** {@link #loadRefusal} for a state the caller already read, so one read decides a gate. */
	public static String refusal(ProjectionState state) {
		return state.converging() ? "a projection update has not finished (transaction " + state.transactionId() + ")" : null;
	}

	/** Whether a boot must settle the projection before any loader takes it: converging, or unreadable contents (P4, P6). A physical read failure propagates. */
	public static boolean owed(ClientStorage storage) throws IOException {
		try {
			return read(storage).converging();
		} catch (UnreadableException e) {
			return true;
		}
	}

	/**
	 * P8: the read a writer makes. A physical failure is recorded beside the document; while it is this boot's own it
	 * propagates as {@link UnavailableException} naming the file, so the gates keep refusing and nothing is repaired on
	 * what may be a transient hold. Once it has persisted across a boot, the document is renamed aside (which needs only
	 * the directory) and reported as unreadable, so the caller runs the repair P6 makes safe. A read that succeeds clears
	 * the record.
	 */
	static ProjectionState readForWriter(ClientStorage storage) throws IOException {
		Path file = storage.projectionStateFile();
		Path record = file.resolveSibling(READ_FAILURE_FILE);
		ProjectionState state;
		try {
			state = read(file);
		} catch (UnreadableException unreadable) {
			throw unreadable;
		} catch (IOException physical) {
			String earlier = recordedProcess(record);
			if (earlier == null || earlier.equals(process)) {
				recordReadFailure(record, physical);
				throw new UnavailableException("Cannot read the projection state " + file + " (" + physical + "); fix its permissions or delete it."
						+ " If it is still unreadable at the next boot it is set aside and repaired without touching the projection.", physical);
			}
			Path aside = file.resolveSibling(file.getFileName() + DurableFiles.CORRUPT_ASIDE_MARKER + UUID.randomUUID());
			try {
				Files.move(file, aside);
			} catch (IOException stuck) {
				stuck.addSuppressed(physical);
				throw new UnavailableException("Cannot read the projection state " + file + " since an earlier boot, and cannot set it aside (" + stuck + "); fix its permissions or delete it", stuck);
			}
			Files.deleteIfExists(record);
			throw new UnreadableException("The projection state " + file + " stayed unreadable across boots (" + physical + "); set aside to " + aside, physical);
		}
		Files.deleteIfExists(record);
		return state;
	}

	private static String recordedProcess(Path record) {
		try {
			ReadFailureFields fields = GSON.fromJson(Files.readString(record, StandardCharsets.UTF_8), ReadFailureFields.class);
			return fields == null || fields.process == null ? "unknown" : fields.process;
		} catch (NoSuchFileException missing) {
			return null;
		} catch (IOException | RuntimeException unusable) {
			// A record nobody can read was still written by some earlier run.
			return "unknown";
		}
	}

	private static void recordReadFailure(Path record, IOException physical) {
		ReadFailureFields fields = new ReadFailureFields();
		fields.process = process;
		fields.reason = String.valueOf(physical);
		fields.at = System.currentTimeMillis();
		try {
			if (process.equals(recordedProcess(record))) return;
			ConfigTools.writeAtomicCompact(record, fields);
		} catch (IOException | RuntimeException unwritable) {
			// Then the document cannot be set aside either; the failure stays reported by name on every boot.
			physical.addSuppressed(unwritable);
		}
	}

	/** P6: sets unreadable contents aside as evidence, so the repair that follows writes a fresh document. */
	static void setAsideUnreadable(ClientStorage storage, UnreadableException cause) throws IOException {
		LOGGER.error("The projection state document is unreadable; keeping it as evidence and repairing it", cause);
		Path file = storage.projectionStateFile();
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) DurableFiles.setAside(file, "Projection state", cause);
		// The temporary of an interrupted non-atomic replace (P7) is evidence too: it is no longer the next document.
		Path parent = file.getParent();
		String prefix = "." + file.getFileName() + ".";
		try (Stream<Path> siblings = Files.list(parent)) {
			for (Path sibling : siblings.toList())
				if (sibling.getFileName().toString().startsWith(prefix) && sibling.getFileName().toString().endsWith(DurableFiles.TEMPORARY_SUFFIX))
					Files.move(sibling, parent.resolve(file.getFileName() + DurableFiles.CORRUPT_ASIDE_MARKER + "temporary-" + sibling.getFileName().toString().substring(1)));
		}
	}

	/**
	 * P2: persists the converging phase with the tree to fall back to and the digest of the tree it converges to;
	 * re-entering keeps the first fallback, which is the last complete tree.
	 */
	static ProjectionState converging(ClientStorage storage, String transactionId, List<Row> fallback, String targetDigest) throws IOException {
		ProjectionState current = read(storage);
		if (current.converging())
			return write(storage, new ProjectionState(Phase.CONVERGING, current.revision + 1, transactionId, current.committedToken, current.fallbackSource, null, targetDigest, current.fallback));
		return write(storage, new ProjectionState(Phase.CONVERGING, current.revision + 1, transactionId, committedToken(storage), Fallback.RECORDED, null, targetDigest, fallback));
	}

	static ProjectionState converging(ClientStorage storage, String transactionId, List<Row> fallback) throws IOException {
		return converging(storage, transactionId, fallback, null);
	}

	/** P6 repair with a journal pending: converging, falling back to the committed generation, until the journal rolls forward. */
	static ProjectionState repairConverging(ClientStorage storage) throws IOException {
		return write(storage, new ProjectionState(Phase.CONVERGING, System.currentTimeMillis(), null, committedToken(storage), Fallback.COMMITTED, null, null, List.of()));
	}

	/** P6 repair with no journal: nothing can be converging, so {@code active/} is vouched for as it stands, content unknown. */
	static ProjectionState repairSettled(ClientStorage storage) throws IOException {
		return write(storage, new ProjectionState(Phase.SETTLED, System.currentTimeMillis(), null, null, Fallback.RECORDED, null, null, List.of()));
	}

	/**
	 * Back to loadable after the transaction committed (P1): the settled tree is the convergence's target. A no-op when
	 * already settled by this transaction, so a replayed tail does not bump the revision.
	 */
	static ProjectionState settled(ClientStorage storage, String transactionId) throws IOException {
		ProjectionState current;
		try {
			current = read(storage);
		} catch (UnreadableException e) {
			// The transaction committed after converging, so the tree is complete; only the document needs repair.
			setAsideUnreadable(storage, e);
			return write(storage, new ProjectionState(Phase.SETTLED, System.currentTimeMillis(), transactionId, null, Fallback.RECORDED, null, null, List.of()));
		}
		if (!current.converging() && Objects.equals(current.transactionId, transactionId) && Files.exists(storage.projectionStateFile(), LinkOption.NOFOLLOW_LINKS)) return current;
		String digest = current.converging() ? current.targetDigest : current.contentDigest;
		return write(storage, new ProjectionState(Phase.SETTLED, current.revision + 1, transactionId, null, Fallback.RECORDED, digest, null, List.of()));
	}

	/** Back to loadable after a restore put {@code rows} back (P4). */
	static ProjectionState restored(ClientStorage storage, List<Row> rows) throws IOException {
		ProjectionState current = read(storage);
		return write(storage, new ProjectionState(Phase.SETTLED, current.revision + 1, null, null, Fallback.RECORDED, rows == null ? null : digest(rows), null, List.of()));
	}

	/**
	 * P1/P5 for a writer outside the commit (offline repair): {@code active/} changed while staying complete, so the
	 * revision moves and the content digest is no longer known. A converging document stays converging.
	 */
	public static void touched(ClientStorage storage) throws IOException {
		ProjectionState current = read(storage);
		write(storage, new ProjectionState(current.phase, current.revision + 1, current.transactionId, current.committedToken, current.fallbackSource, null, current.targetDigest, current.fallback));
	}

	/** The digest of a complete tree: its rows in path order. */
	static String digest(List<Row> rows) {
		try {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			for (Row row : rows.stream().sorted(Comparator.comparing(Row::path)).toList())
				sha1.update((row.path() + "\0" + row.sha1() + "\0" + row.size() + "\n").getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(sha1.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The content token the active state names now, or null without one. */
	static String committedToken(ClientStorage storage) throws IOException {
		var active = storage.readActiveState();
		return active == null ? null : active.contentToken;
	}

	private static ProjectionState write(ClientStorage storage, ProjectionState state) throws IOException {
		Fields fields = new Fields();
		fields.phase = state.phase;
		fields.revision = state.revision;
		fields.transactionId = state.transactionId;
		fields.contentDigest = state.contentDigest;
		if (state.converging()) {
			fields.committedToken = state.committedToken;
			fields.fallbackSource = state.fallbackSource;
			fields.targetDigest = state.targetDigest;
			fields.fallback = new ArrayList<>();
			for (Row row : state.fallback) {
				RowFields persisted = new RowFields();
				persisted.path = row.path();
				persisted.sha1 = row.sha1();
				persisted.size = row.size();
				fields.fallback.add(persisted);
			}
		}
		Files.createDirectories(storage.projectionStateFile().getParent());
		ConfigTools.writeAtomicCompact(storage.projectionStateFile(), fields);
		return state;
	}
}
