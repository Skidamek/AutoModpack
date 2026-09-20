package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOADER_MANAGER;
import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.storage.StoragePaths.*;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The only authority for client-side AutoModpack paths.
 *
 * <p>
 * Every client entry point, including the detached helper, constructs this
 * object from the game directory. The active projection is deliberately a
 * fixed path; content tokens identify immutable generations, not directories
 * exposed to the game.
 * </p>
 */
public final class ClientStorage {
	private static final Map<Path, WeakReference<ClientStorage>> OPEN_STORAGE = new HashMap<>();
	private static volatile IOException scanTimeStateFailure;
	private final Path gameDirectory;
	private final Path automodpackDirectory;
	private final Path clientDirectory;
	private final DataRootResolver.Location dataLocation;
	private final Path dataDirectory;
	private final Path objectsDirectory;
	private final Path overlaysDirectory;
	private final Path generatedCopiesDirectory;
	private final Path activeDirectory;
	private final Path incomingDirectory;
	private final Path backupDirectory;
	private final Path stateFile;
	private final Path transactionFile;
	private final Path repairJournalFile;
	private final Path mutationLockFile;
	private final Path selectionFile;
	private final Path restartLoopStateFile;
	private final Path stuckTransactionStateFile;
	private final Path clientConfigFile;
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

	private ClientStorage(DataRootResolver.Location dataLocation) {
		this.dataLocation = Objects.requireNonNull(dataLocation, "data location");
		this.gameDirectory = requireDirectoryPath(dataLocation.ownerPath(), "game directory");
		this.automodpackDirectory = this.gameDirectory.resolve(AUTOMODPACK_DIR).normalize();
		this.clientDirectory = this.gameDirectory.resolve(CLIENT_DIR).normalize();
		this.dataDirectory = dataLocation.root();
		DataRootResolver.Layout dataLayout = dataLocation.layout();
		this.objectsDirectory = dataLayout.objectsDirectory();
		this.overlaysDirectory = this.gameDirectory.resolve(CLIENT_OVERLAYS_DIR).normalize();
		this.generatedCopiesDirectory = this.gameDirectory.resolve(CLIENT_GENERATED_COPIES_DIR).normalize();
		this.activeDirectory = this.gameDirectory.resolve(CLIENT_ACTIVE_DIR).normalize();
		this.incomingDirectory = this.gameDirectory.resolve(CLIENT_INCOMING_DIR).normalize();
		this.backupDirectory = this.gameDirectory.resolve(CLIENT_BACKUP_DIR).normalize();
		this.stateFile = this.gameDirectory.resolve(CLIENT_ACTIVE_STATE_FILE).normalize();
		this.transactionFile = this.gameDirectory.resolve(CLIENT_TRANSACTION_FILE).normalize();
		this.repairJournalFile = this.gameDirectory.resolve(CLIENT_REPAIR_FILE).normalize();
		this.mutationLockFile = this.gameDirectory.resolve(CLIENT_MUTATION_LOCK_FILE).normalize();
		this.selectionFile = this.gameDirectory.resolve(CLIENT_SELECTION_FILE).normalize();
		this.restartLoopStateFile = this.gameDirectory.resolve(CLIENT_RESTART_LOOP_STATE_FILE).normalize();
		this.stuckTransactionStateFile = this.gameDirectory.resolve(CLIENT_STUCK_TRANSACTION_STATE_FILE).normalize();
		this.clientConfigFile = this.gameDirectory.resolve(CLIENT_CONFIG_FILE).normalize();
		this.modpackContentTempFile = this.gameDirectory.resolve(CLIENT_CONTENT_TEMP_FILE).normalize();
		this.historyDirectory = this.gameDirectory.resolve(CLIENT_HISTORY_DIR).normalize();
		this.stateHistoryDirectory = this.gameDirectory.resolve(CLIENT_STATE_HISTORY_DIR).normalize();
		this.journalTempFile = this.gameDirectory.resolve(CLIENT_JOURNAL_TEMP_FILE).normalize();
		this.bootstrapFile = this.gameDirectory.resolve(BOOTSTRAP_FILE).normalize();
		this.fileCacheDirectory = dataLayout.fileCacheDirectory();
		this.modCacheDirectory = dataLayout.modCacheDirectory();
		this.platformCacheDirectory = dataLayout.platformCacheDirectory();
		this.packsDirectory = dataLayout.packsDirectory();
		this.knownHostsFile = dataLayout.knownHostsFile();
		this.knownHostsLockFile = dataLayout.knownHostsLockFile();
		validateLayout();
	}

	public static synchronized ClientStorage open(Path gameDirectory) {
		// The detached helper and unit tests run without a loader environment (null dist); there the check stays silent.
		LoaderManagerService.EnvironmentType environment = LOADER_MANAGER == null ? null : LOADER_MANAGER.getEnvironmentType();
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

	public Path generatedCopiesFile(String modpackId, String contentToken, String selectionDigest) {
		Path packRoot = generatedCopiesDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
		Path generationRoot = packRoot.resolve(HashUtils.requireDigest(contentToken, "generation ID")).normalize();
		Path file = generationRoot.resolve(HashUtils.requireDigest(selectionDigest, "generated-copy selection digest") + ".json").normalize();
		if (!file.startsWith(generationRoot)) throw new IllegalArgumentException("Generated-copy state escaped its generation root");
		return file;
	}

	public Path generatedCopiesGenerationDirectory(String modpackId, String contentToken) {
		Path root = generatedCopiesDirectory.resolve(ModpackId.requireValid(modpackId)).resolve(HashUtils.requireDigest(contentToken, "generation ID")).normalize();
		if (!root.startsWith(generatedCopiesDirectory)) throw new IllegalArgumentException("Generated-copy state escaped its root");
		return root;
	}

	public Path generatedCopiesPackDirectory(String modpackId) {
		return generatedCopiesDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
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

	public Path incomingDirectory() {
		return incomingDirectory;
	}

	public Path backupDirectory() {
		return backupDirectory;
	}

	public Path recoveredDirectory() {
		return gameDirectory.resolve(RECOVERED_DIR).normalize();
	}

	public Path stagingDirectory() {
		return dataLocation.layout().stagingDirectory();
	}

	public Path stateFile() {
		return stateFile;
	}

	public Path transactionFile() {
		return transactionFile;
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

	public Path stuckTransactionStateFile() {
		return stuckTransactionStateFile;
	}

	public Path restartLoopStateFile() {
		return restartLoopStateFile;
	}

	public Path clientConfigFile() {
		return clientConfigFile;
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

	public Path stateHistoryDirectory() {
		return stateHistoryDirectory;
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

	public ClientOverlaySnapshot overlaySnapshot(String modpackId, FileCache cache) throws IOException {
		return ClientOverlaySnapshot.capture(this, modpackId, cache);
	}

	/** Creates the complete client storage layout during application bootstrap. */
	private void initialize() throws IOException {
		FileTrees.createManagedDirectory(clientDirectory, "client state root");
		FileTrees.createManagedDirectory(objectsDirectory, "client object store");
		FileTrees.createManagedDirectory(fileCacheDirectory, "file cache");
		FileTrees.createManagedDirectory(modCacheDirectory, "mod metadata cache");
		FileTrees.createManagedDirectory(platformCacheDirectory, "platform cache");
		FileTrees.createManagedDirectory(packsDirectory, "shared pack state");
		FileTrees.createManagedDirectory(overlaysDirectory, "client overlays");
		FileTrees.createManagedDirectory(generatedCopiesDirectory, "client generated-copy state");
		FileTrees.createManagedDirectory(stagingDirectory(), "shared publication staging");
		FileTrees.createManagedDirectory(historyDirectory, "client journal mirrors");
		FileTrees.createManagedDirectory(stateHistoryDirectory, "client state history");
		FileTrees.createManagedDirectory(stateHistoryTreesDirectory(), "instance trees");
	}

	/**
	 * Scan-time approximation of the projection gate in the projection loader: whether an active
	 * projection exists that the on-disk selection would load. Runs before preload, so it reads
	 * files only; the authoritative decision applies the boot-recovered configuration later.
	 * Returns the projection's mods directory to hand to loaders that discover mods by directory,
	 * or {@code null} when the projection must not load.
	 */
	public static Path loadableProjectionModsDirectory(Path gameDirectory) {
		Path activeDirectory = gameDirectory.resolve(CLIENT_ACTIVE_DIR).normalize();
		if (!Files.isDirectory(activeDirectory, LinkOption.NOFOLLOW_LINKS)) return null;
		Path activeModsDirectory = activeDirectory.resolve(ModpackPathPolicy.MODS_ROOT);
		if (!Files.isDirectory(activeModsDirectory, LinkOption.NOFOLLOW_LINKS)) return null;

		ClientStorageJsons.ClientGenerationStateFields state;
		try {
			state = ConfigTools.readUnique(gameDirectory.resolve(CLIENT_ACTIVE_STATE_FILE).normalize(),
					ClientStorageJsons.ClientGenerationStateFields.class, "Client active state", ClientStorage::validatedActiveState).orElse(null);
		} catch (IOException e) {
			// The state is durable, so this is a locked or unreadable file, not corrupt content. The gate cannot decide,
			// and returning null quietly would let preload load a projection the directory-scanning loaders never
			// received, so the failure is recorded and the authoritative load refuses to run on this boot.
			LOGGER.error("Failed to read the client active state; not exposing the projection to directory-scanning loaders", e);
			scanTimeStateFailure = e;
			return null;
		}
		if (state == null) return null;

		ClientConfigJsons.ClientConfigFieldsV3 config = ConfigTools.read(gameDirectory.resolve(CLIENT_CONFIG_FILE).normalize(), ClientConfigJsons.ClientConfigFieldsV3.class).orElse(null);
		if (activeSelectionMismatch(config, state) != null) return null;
		return activeModsDirectory;
	}

	/** Why the persisted active selection does not match the configured one, or null when the projection would load. */
	public static String activeSelectionMismatch(ClientConfigJsons.ClientConfigFieldsV3 config, ClientStorageJsons.ClientGenerationStateFields state) {
		if (config == null || !config.hasSelectedModpack()) return "no modpack is selected";
		if (!ModpackId.isValid(config.selectedModpackId)) return "the configured selected modpack ID is invalid: " + config.selectedModpackId;
		if (state == null) return "the active state is missing";
		if (!config.selectedModpackId.equals(state.modpackId)) return "the active state belongs to " + state.modpackId + ", but the selected modpack is " + config.selectedModpackId;
		return null;
	}

	/**
	 * The scan-time gate's active-state read failure, when it could not decide whether the projection would load. The
	 * authoritative load refuses the projection on a boot where this is set: the projection would load without the
	 * loaders that discover mods by directory ever receiving it. A process-lifetime latch, not storage state: the
	 * scan-time hook runs in the loader's own context and can only hand back a directory, so the failure crosses to
	 * the authoritative load through this static and dies with the boot.
	 */
	public static IOException scanTimeStateFailure() {
		return scanTimeStateFailure;
	}

	/**
	 * The active pack pointer, or null when none was persisted yet. Unusable content fails this boot in place and is
	 * never set aside: the pointer carries the detach flag, so continuing as empty would silently rejoin enforcement.
	 */
	public ClientStorageJsons.ClientGenerationStateFields readActiveState() throws IOException {
		return ConfigTools.readUnique(stateFile, ClientStorageJsons.ClientGenerationStateFields.class, "Client active state", ClientStorage::validatedActiveState).orElse(null);
	}

	private static ClientStorageJsons.ClientGenerationStateFields validatedActiveState(ClientStorageJsons.ClientGenerationStateFields state) {
		if (!ModpackId.isValid(state.modpackId) || !HashUtils.isSha1(state.contentToken) || !"ACTIVE".equals(state.status))
			throw new IllegalArgumentException("Client active state identity is invalid");
		OwnershipLedger.fromFields(state.ownershipLedger);
		if (!state.modpackId.equals(state.ownershipLedger.modpackId)) throw new IllegalArgumentException("Client active state and its ledger belong to different modpacks");
		return state;
	}

	public void writeActiveState(String modpackId, String contentToken, GenerationJsons.OwnershipLedgerFields ownershipLedger) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = new ClientStorageJsons.ClientGenerationStateFields();
		state.modpackId = ModpackId.requireValid(modpackId);
		state.contentToken = HashUtils.requireDigest(contentToken, "generation ID");
		state.ownershipLedger = Objects.requireNonNull(ownershipLedger, "ownership ledger");
		state.detached = currentDetachmentFor(state.modpackId);
		Files.createDirectories(stateFile.getParent());
		ConfigTools.writeAtomic(stateFile, state);
	}

	/**
	 * The persistent detachment flag of the active pack: commits republish the same pack's sovereignty, so only an
	 * explicit attach or a cleared state may end it. An unreadable pointer fails the write instead of clearing the flag.
	 */
	private boolean currentDetachmentFor(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields current = readActiveState();
		return current != null && current.modpackId.equals(modpackId) && current.detached;
	}

	public void clearActiveState() throws IOException {
		Files.deleteIfExists(stateFile);
	}

	/** Whether the active pack runs detached: local sovereignty over server enforcement. Packs without the active state never are. */
	public boolean isDetached(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = readActiveState();
		return state != null && state.modpackId.equals(ModpackId.requireValid(modpackId)) && state.detached;
	}

	/** Sets the active pack's detachment flag; a no-op for packs without the active state, since the flag lives there. */
	public void setDetached(String modpackId, boolean detached) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		ClientStorageJsons.ClientGenerationStateFields state = readActiveState();
		if (state == null || !state.modpackId.equals(normalizedModpackId) || state.detached == detached) return;
		state.detached = detached;
		Files.createDirectories(stateFile.getParent());
		ConfigTools.writeAtomic(stateFile, state);
	}

	private void validateLayout() {
		validateWithin(gameDirectory, automodpackDirectory);
		validateWithin(automodpackDirectory, clientDirectory, clientConfigFile, bootstrapFile, gameDirectory.resolve(RECOVERED_DIR));
		validateWithin(clientDirectory, overlaysDirectory, generatedCopiesDirectory, activeDirectory, incomingDirectory, backupDirectory,
				historyDirectory, stateHistoryDirectory, stateHistoryTreesDirectory(), stateFile, transactionFile, repairJournalFile, mutationLockFile, selectionFile, restartLoopStateFile, stuckTransactionStateFile,
				modpackContentTempFile,
				journalTempFile);
		validateWithin(dataDirectory, objectsDirectory, fileCacheDirectory, modCacheDirectory, platformCacheDirectory, packsDirectory, stagingDirectory(), knownHostsFile, knownHostsLockFile);
	}

	private static void validateWithin(Path parent, Path... children) {
		for (Path child : children)
			if (!child.startsWith(parent)) throw new IllegalArgumentException("Client storage path escaped " + parent + ": " + child);
	}

	private static Path requireDirectoryPath(Path path, String description) {
		return Objects.requireNonNull(path, description).toAbsolutePath().normalize();
	}

	private static String requireLogicalPath(String value) {
		return LogicalPath.requireCanonical(value);
	}

	private static Path resolveLogical(Path root, String logicalPath) {
		return LogicalPath.resolve(root, logicalPath);
	}

}
