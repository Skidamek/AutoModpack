package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/**
 * Resolves the client projection used for planning and update checks.
 *
 * <p>
 * The committed {@code active/} tree is the projection the game loads. A pending
 * transaction is only treated as that projection after publication has started
 * (incoming/backup exists, or the phase is already swapping). An unpublished
 * deferred request keeps live observation so extras and missing files stay
 * visible. Game-directory mutations remain an independent observation, with
 * pending live paths attached so a replan can skip work the transaction already
 * owns.
 * </p>
 */
public final class ClientProjectionView {
	private final ClientStorage storage;

	private ClientProjectionView(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage, "storage");
	}

	public static ClientProjectionView open(ClientStorage storage) {
		return new ClientProjectionView(storage);
	}

	/** Returns the target represented by the published projection. */
	public ModpackJsons.ModpackContentFields target() throws IOException {
		UpdateTransaction pending = readPending();
		if (publicationStarted(storage, pending)) {
			ModpackJsons.ModpackContentFields staged = stagedTarget(pending);
			if (staged != null) return staged;
		}
		return committedTarget();
	}

	/**
	 * Rebases the persisted settings on the pending transaction. Planned values win
	 * only while their preconditions still match, so edits made while the game stayed
	 * open cannot be reverted.
	 */
	public ClientConfigJsons.ClientConfigFieldsV3 logicalConfig(ClientConfigJsons.ClientConfigFieldsV3 current) throws IOException {
		Objects.requireNonNull(current, "current config");
		UpdateTransaction pending = readPending();
		if (pending == null || pending.plannedClientConfig == null) return new ClientConfigJsons.ClientConfigFieldsV3(current);
		return rebaseConfig(persistedClientConfig(), pending);
	}

	/** Resolves a logical config from an already-read persisted config without reading the file again. */
	public ClientConfigJsons.ClientConfigFieldsV3 logicalConfig(ClientConfigJsons.ClientConfigFieldsV3 current,
			ClientConfigJsons.ClientConfigFieldsV3 persisted) throws IOException {
		Objects.requireNonNull(current, "current config");
		Objects.requireNonNull(persisted, "persisted config");
		UpdateTransaction pending = readPending();
		if (pending == null || pending.plannedClientConfig == null) return new ClientConfigJsons.ClientConfigFieldsV3(current);
		return rebaseConfig(persisted, pending);
	}

	private ClientConfigJsons.ClientConfigFieldsV3 rebaseConfig(ClientConfigJsons.ClientConfigFieldsV3 persisted, UpdateTransaction pending) throws IOException {
		if (pending.expectedClientConfig == null) throw new IOException("Pending client configuration precondition is missing");
		ClientConfigJsons.ClientConfigFieldsV3 expected = pending.expectedClientConfig;
		ClientConfigJsons.ClientConfigFieldsV3 planned = pending.plannedClientConfig;
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		boolean mayUpdateSelectedModpack = active == null || Objects.equals(persisted.selectedModpackId, active.modpackId);
		return persisted.rebase(expected, planned, mayUpdateSelectedModpack);
	}

	private ClientConfigJsons.ClientConfigFieldsV3 persistedClientConfig() {
		return ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
	}

	/** Captures the projection observation for one planning pass. */
	public Snapshot snapshot(FileMetadataCache cache) throws IOException {
		Objects.requireNonNull(cache, "cache");
		UpdateTransaction pending = readPending();
		if (publicationStarted(storage, pending)) return stagedSnapshot(pending);
		return liveSnapshot(cache, isProjectionTransaction(pending) ? pending : null);
	}

	/** Returns the committed {@code active/} tree the game loads, independent of any unpublished request. */
	public Map<String, UpdatePlan.FileState> liveFiles(FileMetadataCache cache) throws IOException {
		Objects.requireNonNull(cache, "cache");
		return readLiveFiles(cache);
	}

	static boolean publicationStarted(ClientStorage storage, UpdateTransaction transaction) {
		if (!isProjectionTransaction(transaction)) return false;
		if (transaction.phase == UpdateTransaction.Phase.PROJECTED || transaction.phase == UpdateTransaction.Phase.SWAPPING
				|| transaction.phase == UpdateTransaction.Phase.COMMITTED)
			return true;
		try {
			return Files.exists(storage.incomingProjectionDirectory(), LinkOption.NOFOLLOW_LINKS)
					|| Files.exists(storage.backupProjectionDirectory(), LinkOption.NOFOLLOW_LINKS);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private Snapshot stagedSnapshot(UpdateTransaction pending) throws IOException {
		if (pending.targetGenerationId == null) throw new IOException("Pending projection target generation is missing");
		Map<String, UpdatePlan.FileState> files = new LinkedHashMap<>();
		for (UpdatePlan.ProjectedFile projected : pending.projectedFinalState) {
			if (projected == null || projected.root() != UpdatePlan.Root.PROJECTION || !projected.present()) continue;
			if (!HashUtils.isSha1(projected.expectedHash()) || projected.expectedSize() < 0) throw new IOException("Pending projection file metadata is invalid");
			files.put(UpdatePlanner.normalize(projected.relativePath()), new UpdatePlan.FileState(projected.expectedHash(), projected.expectedSize(), true));
		}
		return new Snapshot(stagedTarget(pending), files, pendingGameStates(pending), pending);
	}

	private Snapshot liveSnapshot(FileMetadataCache cache, UpdateTransaction pending) throws IOException {
		return new Snapshot(committedTarget(), readLiveFiles(cache), pendingGameStates(pending), pending);
	}

	private Map<String, UpdatePlan.FileState> readLiveFiles(FileMetadataCache cache) throws IOException {
		Map<String, UpdatePlan.FileState> files = new LinkedHashMap<>();
		Path active = storage.activeDirectory();
		if (Files.isDirectory(active, LinkOption.NOFOLLOW_LINKS)) {
			try (var paths = Files.walk(active)) {
				for (Path path : paths.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).toList()) {
					String relative = UpdatePlanner.normalize(active.relativize(path).toString());
					files.put(relative, new UpdatePlan.FileState(cache.getOrComputeHash(path), Files.size(path), true));
				}
			}
		}
		return files;
	}

	private static Map<String, List<UpdatePlan.FileState>> pendingGameStates(UpdateTransaction pending) throws IOException {
		if (pending == null || pending.projectedFinalState == null) return Map.of();
		Map<String, List<UpdatePlan.FileState>> pendingGameStates = new LinkedHashMap<>();
		try {
			for (UpdatePlan.ProjectedFile projected : pending.projectedFinalState) {
				if (projected == null || projected.root() != UpdatePlan.Root.GAME_DIR) continue;
				String relative = UpdatePlanner.normalize(projected.relativePath());
				UpdatePlan.FileState state = projected.present()
						? new UpdatePlan.FileState(projected.expectedHash(), projected.expectedSize(), true)
						: new UpdatePlan.FileState(null, -1, false);
				pendingGameStates.computeIfAbsent(relative, ignored -> new ArrayList<>()).add(state);
			}
			if (pending.plannedBaselineCaptures != null) {
				for (UpdatePlan.BaselineCapture capture : pending.plannedBaselineCaptures) {
					if (capture == null || capture.root() != UpdatePlan.Root.GAME_DIR) continue;
					String relative = UpdatePlanner.normalize(capture.relativePath());
					UpdatePlan.FileState state = capture.absent()
							? new UpdatePlan.FileState(null, -1, false)
							: new UpdatePlan.FileState(capture.expectedHash(), capture.expectedSize(), true);
					pendingGameStates.computeIfAbsent(relative, ignored -> new ArrayList<>()).add(state);
				}
			}
		} catch (RuntimeException e) {
			throw new IOException("Pending managed live paths are invalid", e);
		}
		return pendingGameStates;
	}

	private ModpackJsons.ModpackContentFields committedTarget() throws IOException {
		return new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current()).map(SelectedModpackTarget::flatTarget).orElse(null);
	}

	private ModpackJsons.ModpackContentFields stagedTarget(UpdateTransaction pending) throws IOException {
		if (!isProjectionTransaction(pending)) return null;
		if (pending.targetGenerationId == null) throw new IOException("Pending projection target generation is missing");
		ModpackJsons.CompleteModpackContentFields fields = new ClientGenerationStore(storage).readFields(pending.targetGenerationId)
				.orElseThrow(() -> new IOException("Staged client generation record is missing: " + pending.targetGenerationId));
		try {
			SelectionIntent intent = pending.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE ? pending.targetIntent() : pending.expectedPriorIntent();
			return SelectedModpackTarget.prepare(fields, pending.expectedPriorIntent(), intent, pending.platform()).flatTarget();
		} catch (RuntimeException e) {
			throw new IOException("Staged client projection target is invalid", e);
		}
	}

	private UpdateTransaction readPending() throws IOException {
		Path path = storage.transactionFile();
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client transaction state is not a regular file: " + path);
		try {
			return ConfigTools.read(path, UpdateTransaction.class).orElseThrow(() -> new IOException("Client transaction state is empty: " + path));
		} catch (RuntimeException e) {
			throw new IOException("Client transaction state is invalid: " + path, e);
		}
	}

	private static boolean isProjectionTransaction(UpdateTransaction transaction) {
		return transaction != null && (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
				|| transaction.purpose == UpdateTransaction.Purpose.MODPACK_DEACTIVATION || transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL)
				&& transaction.projectedFinalState != null;
	}

	public final class Snapshot {
		private final ModpackJsons.ModpackContentFields target;
		private final Map<String, UpdatePlan.FileState> files;
		private final Map<String, List<UpdatePlan.FileState>> pendingGameStates;
		private final UpdateTransaction pending;

		private Snapshot(ModpackJsons.ModpackContentFields target, Map<String, UpdatePlan.FileState> files,
				Map<String, List<UpdatePlan.FileState>> pendingGameStates, UpdateTransaction pending) {
			this.target = target;
			this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
			Map<String, List<UpdatePlan.FileState>> states = new LinkedHashMap<>();
			for (Map.Entry<String, List<UpdatePlan.FileState>> entry : pendingGameStates.entrySet()) states.put(entry.getKey(), List.copyOf(entry.getValue()));
			this.pendingGameStates = Collections.unmodifiableMap(states);
			this.pending = pending;
		}

		public ModpackJsons.ModpackContentFields target() {
			return target;
		}

		public Map<String, UpdatePlan.FileState> files() {
			return files;
		}

		/** Returns whether the observed live state is one of the states already owned by a pending transaction. */
		public boolean matchesPendingGameState(String relativePath, UpdatePlan.FileState observed) {
			if (pending == null || observed == null) return false;
			String relative = UpdatePlanner.normalize(relativePath);
			return pendingGameStates.getOrDefault(relative, List.of()).stream().anyMatch(expected -> expected.regularFile() == observed.regularFile()
					&& expected.size() == observed.size() && Objects.equals(expected.sha1(), observed.sha1()));
		}

		/** Returns pending managed live paths so a replan can observe their real filesystem state. */
		public Set<String> gamePaths() throws IOException {
			if (pending == null) return Set.of();
			try {
				TreeSet<String> paths = new TreeSet<>();
				for (UpdatePlan.ProjectedFile projected : pending.projectedFinalState)
					if (projected != null && projected.root() == UpdatePlan.Root.GAME_DIR) paths.add(UpdatePlanner.normalize(projected.relativePath()));
				return Collections.unmodifiableSet(paths);
			} catch (RuntimeException e) {
				throw new IOException("Pending managed live paths are invalid", e);
			}
		}

		/** Returns the generated-copy state for this logical projection, including an unpublished pending state. */
		public GeneratedCopyState generatedCopies() throws IOException {
			if (pending != null && pending.plannedGeneratedCopies != null) {
				try {
					return GeneratedCopyState.fromFields(pending.plannedGeneratedCopies);
				} catch (RuntimeException e) {
					throw new IOException("Pending generated-copy state is invalid", e);
				}
			}
			if (target == null) return null;
			SelectionIntent intent = new ClientSelectionStore(storage.selectionFile()).get(target.modpackId).orElse(null);
			if (intent == null) return null;
			try {
				return GeneratedCopyState.read(storage, target.modpackId, target.targetGenerationId, UpdateTransaction.digest(intent));
			} catch (RuntimeException e) {
				throw new IOException("Generated-copy state is invalid", e);
			}
		}

		/** Returns safe read-only candidates without exposing the projection's storage policy to callers. */
		public List<Path> sourceCandidates(String relativePath) {
			String relative = UpdatePlanner.normalize(relativePath);
			List<Path> candidates = new ArrayList<>();
			if (pending != null) candidates.add(resolve(storage.incomingProjectionDirectory(), relative));
			candidates.add(storage.activePath(relative));
			UpdatePlan.FileState expected = files.get(relative);
			if (expected != null && expected.sha1() != null) candidates.add(storage.objectFile(expected.sha1()));
			if (pending != null && pending.projectedFinalState != null) {
				for (UpdatePlan.ProjectedFile projected : pending.projectedFinalState) {
					if (projected == null || projected.root() != UpdatePlan.Root.PROJECTION || !projected.present()) continue;
					if (!relative.equals(UpdatePlanner.normalize(projected.relativePath())) || !HashUtils.isSha1(projected.expectedHash())) continue;
					candidates.add(storage.objectFile(projected.expectedHash()));
				}
			}
			return List.copyOf(candidates);
		}

		private Path resolve(Path root, String relative) {
			Path resolved = root.resolve(relative).normalize();
			if (!resolved.startsWith(root)) throw new IllegalArgumentException("Projection source escaped its root");
			return resolved;
		}
	}
}
