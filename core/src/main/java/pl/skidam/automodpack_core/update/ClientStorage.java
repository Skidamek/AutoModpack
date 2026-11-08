package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.storage.StoragePaths.*;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientConfigStore;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.storage.StorageLeftovers;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The client's storage composition root: open and initialize one instance per game directory, own every path of the
 * layout, run locked mutations, and hold the active pack pointer and the editable overlay state.
 *
 * <p>
 * Every client entry point, including the detached helper, constructs this object from the game directory. The active
 * projection is deliberately a fixed path; content tokens identify immutable generations, not directories exposed to
 * the game.
 * </p>
 */
public final class ClientStorage {
	private static final Logger LOGGER = LogManager.getLogger(ClientStorage.class);

	private static final Map<Path, WeakReference<ClientStorage>> OPEN_STORAGE = new HashMap<>();
	private final DataRootResolver.Location dataLocation;
	private final DataRootResolver.Layout dataLayout;
	private final Path gameDirectory;
	private final Path automodpackDirectory;
	private final Path clientDirectory;
	private final Path dataDirectory;
	private final Path objectsDirectory;
	private final Path overlaysDirectory;
	private final Path generatedCopiesDirectory;
	private final Path generatedCopiesFile;
	private final Path activeDirectory;
	private final Path legacyIncomingDirectory;
	private final Path legacyBackupDirectory;
	private final Path stateFile;
	private final Path transactionFile;
	private final Path projectionStateFile;
	private final Path projectionRelaunchFile;
	private final Path projectionRepairBackupsDirectory;
	private final Path repairJournalFile;
	private final Path mutationLockFile;
	private final Path selectionFile;
	private final Path relayConsentFile;
	private final Path restartLoopStateFile;
	private final Path stuckTransactionStateFile;
	private final Path clientConfigFile;
	private final ClientConfigStore clientConfig;
	private final Path selectedModpackFile;
	private final Path modpackContentTempFile;
	private final Path historyDirectory;
	private final Path stateHistoryDirectory;
	private final Path journalTempFile;
	private final Path bootstrapFile;
	private final Path fileCacheDirectory;
	private final Path modCacheDirectory;
	private final Path platformCacheDirectory;
	private final Path packsDirectory;
	private final Path knownHostsFile;
	private final Path knownHostsLockFile;
	private final ActivePackPointer pointer;

	private ClientStorage(DataRootResolver.Location dataLocation) {
		this.dataLocation = Objects.requireNonNull(dataLocation, "data location");
		this.dataLayout = dataLocation.layout();
		this.gameDirectory = requireDirectoryPath(dataLocation.ownerPath(), "game directory");
		this.automodpackDirectory = gameDirectory.resolve(AUTOMODPACK_DIR).normalize();
		this.clientDirectory = gameDirectory.resolve(CLIENT_DIR).normalize();
		this.dataDirectory = dataLocation.root();
		this.objectsDirectory = dataLayout.objectsDirectory();
		this.overlaysDirectory = gameDirectory.resolve(CLIENT_OVERLAYS_DIR).normalize();
		this.generatedCopiesDirectory = gameDirectory.resolve(CLIENT_GENERATED_COPIES_DIR).normalize();
		this.generatedCopiesFile = gameDirectory.resolve(CLIENT_GENERATED_COPIES_FILE).normalize();
		this.activeDirectory = gameDirectory.resolve(CLIENT_ACTIVE_DIR).normalize();
		this.legacyIncomingDirectory = gameDirectory.resolve(CLIENT_LEGACY_INCOMING_DIR).normalize();
		this.legacyBackupDirectory = gameDirectory.resolve(CLIENT_LEGACY_BACKUP_DIR).normalize();
		this.stateFile = gameDirectory.resolve(CLIENT_ACTIVE_STATE_FILE).normalize();
		this.transactionFile = gameDirectory.resolve(CLIENT_TRANSACTION_FILE).normalize();
		this.projectionStateFile = ProjectionState.file(gameDirectory);
		this.projectionRelaunchFile = gameDirectory.resolve(CLIENT_PROJECTION_RELAUNCH_FILE).normalize();
		this.projectionRepairBackupsDirectory = clientDirectory.resolve(ProjectionRepairBackups.DIRECTORY).normalize();
		this.repairJournalFile = gameDirectory.resolve(CLIENT_REPAIR_FILE).normalize();
		this.mutationLockFile = gameDirectory.resolve(CLIENT_MUTATION_LOCK_FILE).normalize();
		this.selectionFile = gameDirectory.resolve(CLIENT_SELECTION_FILE).normalize();
		this.relayConsentFile = gameDirectory.resolve(CLIENT_RELAY_FILE).normalize();
		this.restartLoopStateFile = gameDirectory.resolve(CLIENT_RESTART_LOOP_STATE_FILE).normalize();
		this.stuckTransactionStateFile = gameDirectory.resolve(CLIENT_STUCK_TRANSACTION_STATE_FILE).normalize();
		this.clientConfigFile = gameDirectory.resolve(CLIENT_CONFIG_FILE).normalize();
		this.clientConfig = new ClientConfigStore(clientConfigFile);
		this.selectedModpackFile = gameDirectory.resolve(CLIENT_SELECTED_FILE).normalize();
		this.modpackContentTempFile = gameDirectory.resolve(CLIENT_CONTENT_TEMP_FILE).normalize();
		this.historyDirectory = gameDirectory.resolve(CLIENT_HISTORY_DIR).normalize();
		this.stateHistoryDirectory = gameDirectory.resolve(CLIENT_STATE_HISTORY_DIR).normalize();
		this.journalTempFile = gameDirectory.resolve(CLIENT_JOURNAL_TEMP_FILE).normalize();
		this.bootstrapFile = gameDirectory.resolve(BOOTSTRAP_FILE).normalize();
		this.fileCacheDirectory = dataLayout.fileCacheDirectory();
		this.modCacheDirectory = dataLayout.modCacheDirectory();
		this.platformCacheDirectory = dataLayout.platformCacheDirectory();
		this.packsDirectory = dataLayout.packsDirectory();
		this.knownHostsFile = dataLayout.knownHostsFile();
		this.knownHostsLockFile = dataLayout.knownHostsLockFile();
		this.pointer = new ActivePackPointer(stateFile);
		validateLayout();
	}

	public static synchronized ClientStorage open(Path gameDirectory) {
		// The detached helper and unit tests run without a loader environment (null dist); there the check stays silent.
		LoaderManagerService.EnvironmentType environment = Environment.sideOrNull();
		if (environment != null && environment != LoaderManagerService.EnvironmentType.CLIENT)
			throw new IllegalStateException("Client storage belongs to the client role, but this process runs the " + environment + " environment");
		DataRootResolver.Location dataLocation = DataRootResolver.resolve(requireDirectoryPath(gameDirectory, "game directory"));
		Path canonicalGameDirectory = dataLocation.ownerPath();
		WeakReference<ClientStorage> reference = OPEN_STORAGE.get(canonicalGameDirectory);
		ClientStorage existing = reference == null ? null : reference.get();
		if (existing != null) return existing;
		OPEN_STORAGE.entrySet().removeIf(entry -> entry.getValue().get() == null);
		ClientStorage storage = new ClientStorage(dataLocation);
		try {
			storage.initialize();
		} catch (IOException e) {
			throw new IllegalStateException("Cannot initialize client storage for " + storage.gameDirectory, e);
		}
		try {
			ClientObjectStore.publishOwnership(storage);
		} catch (IOException e) {
			// The receipt is collection bookkeeping: every collection republishes it under the lock first, so a failed
			// publish only makes the next collection refuse to run - it must never cost the game its boot.
			LOGGER.error("Could not publish the client object ownership receipt for {}; content collection stays refused until one publishes: {}", storage.gameDirectory, e.getMessage(), e);
		}
		OPEN_STORAGE.put(canonicalGameDirectory, new WeakReference<>(storage));
		StorageLeftovers.warnAbout(storage.automodpackDirectory);
		return storage;
	}

	public Path gameDirectory() {
		return gameDirectory;
	}

	public Path gamePath(String logicalPath) {
		return resolveLogical(gameDirectory, logicalPath);
	}

	public Path automodpackDirectory() {
		return automodpackDirectory;
	}

	public Path clientDirectory() {
		return clientDirectory;
	}

	public Path dataDirectory() {
		return dataDirectory;
	}

	public DataRootResolver.Location dataLocation() {
		return dataLocation;
	}

	public Path objectsDirectory() {
		return objectsDirectory;
	}

	public Path objectFile(String sha1) {
		return DataRootResolver.objectFile(objectsDirectory, sha1);
	}

	public Path overlaysDirectory() {
		return overlaysDirectory;
	}

	public Path generatedCopiesDirectory() {
		return generatedCopiesDirectory;
	}

	public Path generatedCopiesFile() {
		return generatedCopiesFile;
	}

	/** rc.1/rc.2 per-identity path; {@link GeneratedCopyState#migrateLegacyTree} imports these into {@link #generatedCopiesFile()} once, at initialization. */
	public Path legacyGeneratedCopiesFile(String modpackId, String contentToken, String selectionDigest) {
		Path packRoot = generatedCopiesDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
		Path generationRoot = packRoot.resolve(HashUtils.requireDigest(contentToken, "generation ID")).normalize();
		Path file = generationRoot.resolve(HashUtils.requireDigest(selectionDigest, "generated-copy selection digest") + ".json").normalize();
		if (!file.startsWith(generationRoot)) throw new IllegalArgumentException("Generated-copy state escaped its generation root");
		return file;
	}

	public Path activeDirectory() {
		return activeDirectory;
	}

	public Path activePath(String logicalPath) {
		return resolveLogical(activeDirectory, logicalPath);
	}

	/** The one physical root directory of an update-plan root for the given modpack. */
	public Path root(Root root, String modpackId) {
		return switch (root) {
			case PROJECTION -> activeDirectory;
			case OVERLAY -> overlayDirectory(modpackId);
			case GAME_DIR -> gameDirectory;
		};
	}

	/** The one physical file location of a root-relative logical path for the given modpack. */
	public Path rootedPath(Root root, String modpackId, String logicalPath) {
		return switch (root) {
			case GAME_DIR -> gamePath(logicalPath);
			case OVERLAY -> overlayFile(modpackId, logicalPath);
			case PROJECTION -> activePath(logicalPath);
		};
	}

	/**
	 * The two working directories of an older version's projection swap. Nothing writes them any more; they are read
	 * only to roll a journal that version left behind forward, and swept ({@link PublicationSweep#settleLegacyDirectories}).
	 */
	public Path legacyIncomingDirectory() {
		return legacyIncomingDirectory;
	}

	public Path legacyBackupDirectory() {
		return legacyBackupDirectory;
	}

	public Path recoveredDirectory() {
		return gameDirectory.resolve(RECOVERED_DIR).normalize();
	}

	public Path stagingDirectory() {
		return dataLocation.stagingDirectory();
	}

	public Path stateFile() {
		return stateFile;
	}

	public Path transactionFile() {
		return transactionFile;
	}

	/** The one-shot guard of the relaunch after recovery changed what a directory-scanning loader took (P5). */
	public Path projectionRelaunchFile() {
		return projectionRelaunchFile;
	}

	/** Where a repair keeps the player's own files before the projection replaces them. */
	Path projectionRepairBackupsDirectory() {
		return projectionRepairBackupsDirectory;
	}

	/** The projection state document: whether {@code active/} is loadable as it stands ({@link ProjectionState}). */
	public Path projectionStateFile() {
		return projectionStateFile;
	}

	public Path repairJournalFile() {
		return repairJournalFile;
	}

	public Path mutationLockFile() {
		return mutationLockFile;
	}

	public Path selectionFile() {
		return selectionFile;
	}

	public Path relayConsentFile() {
		return relayConsentFile;
	}

	public Path stuckTransactionStateFile() {
		return stuckTransactionStateFile;
	}

	public Path restartLoopStateFile() {
		return restartLoopStateFile;
	}

	public Path clientConfigFile() {
		return clientConfigFile;
	}

	/** The document this instance runs on: the only door to reading or changing the client config. */
	public ClientConfigStore clientConfig() {
		return clientConfig;
	}

	/** The running instance's storage; one client instance per process. */
	public static ClientStorage current() {
		return open(GameDirectory.current());
	}

	public Path selectedModpackFile() {
		return selectedModpackFile;
	}

	public String selectedModpackId() {
		ClientConfigJsons.SelectedModpackFields fields = ConfigTools.read(selectedModpackFile, ClientConfigJsons.SelectedModpackFields.class).orElse(null);
		if (fields == null || fields.modpackId == null) return "";
		return fields.modpackId;
	}

	public void writeSelectedModpackId(String modpackId) throws IOException {
		ClientConfigJsons.SelectedModpackFields fields = new ClientConfigJsons.SelectedModpackFields();
		fields.modpackId = modpackId == null ? "" : modpackId;
		Files.createDirectories(selectedModpackFile.getParent());
		ConfigTools.writeAtomic(selectedModpackFile, fields);
	}

	public Path fileCacheDirectory() {
		return fileCacheDirectory;
	}

	public Path modCacheDirectory() {
		return modCacheDirectory;
	}

	public Path platformCacheDirectory() {
		return platformCacheDirectory;
	}

	public Path packsDirectory() {
		return packsDirectory;
	}

	public Path knownHostsFile() {
		return knownHostsFile;
	}

	public Path knownHostsLockFile() {
		return knownHostsLockFile;
	}

	public Path modpackContentTempFile() {
		return modpackContentTempFile;
	}

	public Path historyDirectory() {
		return historyDirectory;
	}

	/** The instance timeline journal: one small line per snapshot. */
	public Path stateHistoryJournalFile() {
		return stateHistoryDirectory.resolve("journal.jsonl").normalize();
	}

	public Path stateHistoryTreesDirectory() {
		return stateHistoryDirectory.resolve("trees").normalize();
	}

	public Path stateHistoryTreeFile(String sha1) {
		return stateHistoryTreesDirectory().resolve(HashUtils.normalizeSha1(sha1)).normalize();
	}

	public Path historyPackDirectory(String modpackId) {
		return historyDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
	}

	/** The per-pack replica of the server journal file; a pure server artifact the client only swaps atomically. */
	public Path historyJournalFile(String modpackId) {
		return historyPackDirectory(modpackId).resolve(SERVER_JOURNAL_FILE.getFileName().toString()).normalize();
	}

	/** The per-pack replica of the server head document; like the journal mirror, a pure server artifact the client only swaps atomically. */
	public Path historyHeadFile(String modpackId) {
		return historyPackDirectory(modpackId).resolve("head.json").normalize();
	}

	/** The per-pack receipt of the last manual history compaction; informational state for the storage UI. */
	public Path historyCompactionReceiptFile(String modpackId) {
		return historyPackDirectory(modpackId).resolve("compaction.json").normalize();
	}

	public Path journalTempFile() {
		return journalTempFile;
	}

	public Path bootstrapFile() {
		return bootstrapFile;
	}

	public Path modsDirectory() {
		return gamePath(ModpackPathPolicy.MODS_ROOT);
	}

	public Path connectionFile(String modpackId) {
		return packsDirectory.resolve(ModpackId.requireValid(modpackId)).resolve("connection.json").normalize();
	}

	public Path connectionDirectory(String modpackId) {
		return packsDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
	}

	public Path connectionLockFile(String modpackId) {
		return connectionFile(modpackId).resolveSibling("connection.json.lock");
	}

	public Path overlayDirectory(String modpackId) {
		return overlaysDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
	}

	public Path overlayFile(String modpackId, String logicalPath) {
		Path root = overlayDirectory(modpackId);
		Path resolved = root.resolve(LogicalPath.normalize(logicalPath)).normalize();
		if (!resolved.startsWith(root)) throw new IllegalArgumentException("Overlay path escapes its modpack lineage");
		return resolved;
	}

	public Path overlayStateFile(String modpackId) {
		return overlaysDirectory.resolve(ModpackId.requireValid(modpackId) + ".json").normalize();
	}

	/** The pack's overlay tombstones, or an empty overlay state when none was persisted. Unusable content fails this boot in place so deleted overlay files cannot silently return. */
	public ClientStorageJsons.ClientOverlayFields readOverlayState(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		return ConfigTools.readUnique(overlayStateFile(normalizedModpackId), ClientStorageJsons.ClientOverlayFields.class, "Client overlay state",
				fields -> canonicalTombstones(normalizedModpackId, fields)).orElseGet(() -> {
					ClientStorageJsons.ClientOverlayFields empty = new ClientStorageJsons.ClientOverlayFields();
					empty.modpackId = normalizedModpackId;
					empty.deletedPaths = List.of();
					return empty;
				});
	}

	private static ClientStorageJsons.ClientOverlayFields canonicalTombstones(String modpackId, ClientStorageJsons.ClientOverlayFields state) {
		if (!modpackId.equals(state.modpackId) || state.deletedPaths == null) throw new IllegalArgumentException("Client overlay state identity is invalid");
		List<String> canonical = state.deletedPaths.stream().map(ClientStorage::requireLogicalPath).distinct().sorted().toList();
		if (!canonical.equals(state.deletedPaths)) throw new IllegalArgumentException("Client overlay tombstones are not canonical");
		return state;
	}

	public void writeOverlayState(String modpackId, Set<String> deletedPaths) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		TreeSet<String> canonical = new TreeSet<>();
		for (String path : deletedPaths) canonical.add(requireLogicalPath(path));
		ClientStorageJsons.ClientOverlayFields current = readOverlayState(normalizedModpackId);
		if (current.deletedPaths.equals(List.copyOf(canonical))) return;
		Path stateFile = overlayStateFile(normalizedModpackId);
		if (canonical.isEmpty()) {
			Files.deleteIfExists(stateFile);
			return;
		}
		ClientStorageJsons.ClientOverlayFields state = new ClientStorageJsons.ClientOverlayFields();
		state.modpackId = normalizedModpackId;
		state.deletedPaths = List.copyOf(canonical);
		ConfigTools.writeAtomic(stateFile, state);
	}

	public void clearOverlay(String modpackId) throws IOException {
		FileTrees.delete(overlayDirectory(modpackId));
		Files.deleteIfExists(overlayStateFile(modpackId));
	}

	public String overlayDigest(String modpackId) throws IOException {
		return ClientOverlaySnapshot.capture(this, modpackId, null).digest();
	}

	/** The digest with tripwire-backed hashing: unchanged overlay files cost a stat, not a full read. */
	public String overlayDigest(String modpackId, FileCache cache) throws IOException {
		return ClientOverlaySnapshot.capture(this, modpackId, cache).digest();
	}

	public ClientOverlaySnapshot overlaySnapshot(String modpackId, FileCache cache) throws IOException {
		return ClientOverlaySnapshot.capture(this, modpackId, cache);
	}

	/** Creates the complete client storage layout during application bootstrap, and imports the one legacy layout that predates it. */
	private void initialize() throws IOException {
		FileTrees.createManagedDirectory(clientDirectory, "client state root");
		FileTrees.createManagedDirectory(objectsDirectory, "client object store");
		FileTrees.createManagedDirectory(fileCacheDirectory, "file cache");
		FileTrees.createManagedDirectory(modCacheDirectory, "mod metadata cache");
		FileTrees.createManagedDirectory(platformCacheDirectory, "platform cache");
		FileTrees.createManagedDirectory(packsDirectory, "shared pack state");
		FileTrees.createManagedDirectory(overlaysDirectory, "client overlays");
		FileTrees.createManagedDirectory(stagingDirectory(), "client download staging");
		FileTrees.createManagedDirectory(historyDirectory, "client journal mirrors");
		FileTrees.createManagedDirectory(stateHistoryDirectory, "client state history");
		FileTrees.createManagedDirectory(stateHistoryTreesDirectory(), "instance trees");
		// The only place a durable migration runs, under the mutation lock: a reader that rewrote state made every
		// unlocked caller a writer, and two processes racing the index dropped each other's entries.
		GeneratedCopyState.migrateLegacyTree(this);
	}

	/** Why selected and active-state disagree, or null when the projection may load (matching pointer, or no pointer). */
	public static String activeSelectionMismatch(ClientConfigJsons.SelectedModpackFields selected, ClientStorageJsons.ClientGenerationStateFields state) {
		return ActivePackPointer.selectionMismatch(selected, state);
	}

	public static String activeSelectionMismatch(String followId, ClientStorageJsons.ClientGenerationStateFields state) {
		return ActivePackPointer.selectionMismatch(followId, state);
	}

	static ClientStorageJsons.ClientGenerationStateFields validatedActiveState(ClientStorageJsons.ClientGenerationStateFields state) {
		return ActivePackPointer.validated(state);
	}

	/** The active pack pointer, or null when none was persisted yet. */
	public ClientStorageJsons.ClientGenerationStateFields readActiveState() throws IOException {
		return pointer.read();
	}

	/** Publishes the pointer for what the client now runs. */
	public void writeActiveState(PackDocument document, SelectionIntent applied) throws IOException {
		pointer.write(document, applied);
	}

	/** Publishes a generation with the detachment chosen by an explicit state transition. */
	public void writeActiveState(PackDocument document, SelectionIntent applied, boolean detached) throws IOException {
		pointer.write(document, applied, detached);
	}

	/** Stages a pointer with no recorded selection: the pre-split shape, whose applied answer falls back to the store. */
	public void writeActiveState(PackDocument document) throws IOException {
		pointer.write(document);
	}

	public void clearActiveState() throws IOException {
		pointer.clear();
	}

	/** Whether the active pack runs detached: local sovereignty over server enforcement. Packs without the active state never are. */
	public boolean isDetached(String modpackId) throws IOException {
		return pointer.isDetached(modpackId);
	}

	/** Sets the active pack's detachment flag; a no-op for packs without the active state, since the flag lives there. */
	public void setDetached(String modpackId, boolean detached) throws IOException {
		pointer.setDetached(modpackId, detached);
	}

	private void validateLayout() {
		validateWithin(gameDirectory, automodpackDirectory);
		validateWithin(automodpackDirectory, clientDirectory, clientConfigFile, bootstrapFile, gameDirectory.resolve(RECOVERED_DIR));
		validateWithin(clientDirectory, overlaysDirectory, generatedCopiesDirectory, generatedCopiesFile, activeDirectory, legacyIncomingDirectory, legacyBackupDirectory, historyDirectory,
				stateHistoryDirectory, stateHistoryTreesDirectory(), stateFile, transactionFile, projectionStateFile, projectionRelaunchFile, projectionRepairBackupsDirectory, repairJournalFile,
				mutationLockFile, selectionFile, relayConsentFile, selectedModpackFile, restartLoopStateFile, stuckTransactionStateFile, modpackContentTempFile, journalTempFile);
		validateWithin(dataDirectory, objectsDirectory, fileCacheDirectory, modCacheDirectory, platformCacheDirectory, packsDirectory, stagingDirectory(), knownHostsFile, knownHostsLockFile);
	}

	private static void validateWithin(Path parent, Path... children) {
		for (Path child : children) if (!child.startsWith(parent)) throw new IllegalArgumentException("Client storage path escaped " + parent + ": " + child);
	}

	static Path requireDirectoryPath(Path path, String description) {
		return Objects.requireNonNull(path, description).toAbsolutePath().normalize();
	}

	private static Path resolveLogical(Path root, String logicalPath) {
		return LogicalPath.resolve(root, logicalPath);
	}

	private static String requireLogicalPath(String value) {
		return LogicalPath.requireCanonical(value);
	}
}
