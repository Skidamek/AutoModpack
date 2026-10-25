package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The one durable answer to "may a loader scan {@code active/} as it stands": the projection state document.
 *
 * <p>
 * Invariants, each enforced where named:
 * <ul>
 * <li><b>P1 (loadable).</b> While the document is absent or {@link Phase#SETTLED}, {@code active/} is one complete
 * projection: the one the last committed transaction converged it to (or, for a detached pack, what the player made of
 * it). Every writer of {@code active/} outside the update commit only re-links files to that same projection.</li>
 * <li><b>P2 (marked before mixed).</b> The commit persists {@link Phase#CONVERGING}, with the complete fallback tree
 * {@code active/} held at that moment, before its first projection write ({@code ProjectionConvergence.begin}), and
 * returns to {@link Phase#SETTLED} only after its transaction's COMMITTED marker, or after the fallback was restored.</li>
 * <li><b>P3 (gates consult it).</b> Every loader gate refuses {@code active/} while the phase is not settled or the
 * document cannot be read: {@link ClientStorage#loadableProjectionModsDirectory}, {@code ClientLaunch.hasActiveProjection}
 * and {@code ProjectionLoader}, through {@link #loadRefusal}.</li>
 * <li><b>P4 (a boot never runs on owed projection work).</b> Boot recovery and the publication sweep restore the
 * fallback from CAS whenever they leave a converging projection behind, so a boot that runs as installed loads a
 * complete pack ({@code BootRecovery.settleProjection}, {@code PublicationSweep}).</li>
 * <li><b>P5 (revision).</b> Every write that may change what {@code active/} holds raises {@link #revision()}, so a
 * loader that scanned before recovery can tell whether it scanned what this boot runs on.</li>
 * </ul>
 */
public final class ProjectionState {
	public enum Phase {
		SETTLED,
		CONVERGING
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
		List<RowFields> fallback;
	}

	static final class RowFields {
		String path;
		String sha1;
		long size;
	}

	/** The state of an instance without a document: older versions only ever left a complete projection behind. */
	private static final ProjectionState LEGACY = new ProjectionState(Phase.SETTLED, 0, null, List.of());

	private final Phase phase;
	private final long revision;
	private final String transactionId;
	private final List<Row> fallback;

	private ProjectionState(Phase phase, long revision, String transactionId, List<Row> fallback) {
		this.phase = Objects.requireNonNull(phase, "phase");
		this.revision = revision;
		this.transactionId = transactionId;
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

	/** While converging: the complete tree {@code active/} held when the convergence began. Empty when settled. */
	public List<Row> fallback() {
		return fallback;
	}

	public static Path file(Path gameDirectory) {
		return gameDirectory.resolve(StoragePaths.CLIENT_PROJECTION_STATE_FILE).normalize();
	}

	public static ProjectionState read(ClientStorage storage) throws IOException {
		return read(storage.projectionStateFile());
	}

	/** Reads without side effects, so a loader hook may call it: unusable content is an IOException, never set aside here. */
	public static ProjectionState read(Path file) throws IOException {
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return LEGACY;
		Fields fields;
		try {
			fields = ConfigTools.read(file, Fields.class).orElse(null);
		} catch (RuntimeException e) {
			throw new IOException("Projection state is unreadable: " + file, e);
		}
		if (fields == null || fields.phase == null || fields.version != 1 || fields.revision < 0) throw new IOException("Projection state is invalid: " + file);
		List<Row> rows = new ArrayList<>();
		try {
			if (fields.fallback != null) for (RowFields row : fields.fallback) rows.add(new Row(row.path, row.sha1, row.size));
		} catch (RuntimeException e) {
			throw new IOException("Projection state fallback is invalid: " + file, e);
		}
		if (fields.phase == Phase.SETTLED && !rows.isEmpty()) throw new IOException("Settled projection state carries a fallback: " + file);
		return new ProjectionState(fields.phase, fields.revision, fields.transactionId, rows);
	}

	/**
	 * Why a loader must not take {@code active/} as it stands right now, or null when it may (P3). An unreadable document
	 * refuses: it cannot prove the tree complete.
	 */
	public static String loadRefusal(Path gameDirectory) {
		try {
			ProjectionState state = read(file(gameDirectory));
			return state.converging() ? "a projection update has not finished (transaction " + state.transactionId() + ")" : null;
		} catch (IOException e) {
			return e.getMessage();
		}
	}

	/** P2: persists the converging phase with the tree to fall back to; re-entering keeps the first fallback, which is the last complete tree. */
	static ProjectionState converging(ClientStorage storage, String transactionId, List<Row> fallback) throws IOException {
		ProjectionState current = read(storage);
		List<Row> kept = current.converging() ? current.fallback() : fallback;
		return write(storage, new ProjectionState(Phase.CONVERGING, current.revision() + 1, transactionId, kept));
	}

	/** Back to loadable (P1); a no-op when already settled by this transaction, so a replayed tail does not bump the revision. */
	static ProjectionState settled(ClientStorage storage, String transactionId) throws IOException {
		ProjectionState current = read(storage);
		if (!current.converging() && Objects.equals(current.transactionId(), transactionId) && Files.exists(storage.projectionStateFile(), LinkOption.NOFOLLOW_LINKS)) return current;
		return write(storage, new ProjectionState(Phase.SETTLED, current.revision() + 1, transactionId, List.of()));
	}

	private static ProjectionState write(ClientStorage storage, ProjectionState state) throws IOException {
		Fields fields = new Fields();
		fields.phase = state.phase;
		fields.revision = state.revision;
		fields.transactionId = state.transactionId;
		if (state.converging()) {
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
