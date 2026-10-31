package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOADER_MANAGER;
import static pl.skidam.automodpack_core.Constants.LOGGER;

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

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.StorageLeftovers;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The client's storage composition root: open and initialize one instance per game directory, run locked mutations, and
 * hold the active pack pointer and the editable overlay state. Every path lives in {@link ClientLayout}, so a layout
 * change never touches the lifecycle code.
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
	private final DataRootResolver.Location dataLocation;
	private final ClientLayout layout;
	private final ActivePackPointer pointer;

	private ClientStorage(DataRootResolver.Location dataLocation) {
		this.dataLocation = Objects.requireNonNull(dataLocation, "data location");
		this.layout = new ClientLayout(dataLocation);
		this.pointer = new ActivePackPointer(layout.stateFile());
	}

	public static synchronized ClientStorage open(Path gameDirectory) {
		// The detached helper and unit tests run without a loader environment (null dist); there the check stays silent.
		LoaderManagerService.EnvironmentType environment = LOADER_MANAGER == null ? null : LOADER_MANAGER.getEnvironmentType();
		if (environment != null && environment != LoaderManagerService.EnvironmentType.CLIENT)
			throw new IllegalStateException("Client storage belongs to the client role, but this process runs the " + environment + " environment");
		DataRootResolver.Location dataLocation = DataRootResolver.resolve(ClientLayout.requireDirectoryPath(gameDirectory, "game directory"));
		Path canonicalGameDirectory = dataLocation.ownerPath();
		WeakReference<ClientStorage> reference = OPEN_STORAGE.get(canonicalGameDirectory);
		ClientStorage existing = reference == null ? null : reference.get();
		if (existing != null) return existing;
		OPEN_STORAGE.entrySet().removeIf(entry -> entry.getValue().get() == null);
		ClientStorage storage = new ClientStorage(dataLocation);
		try {
			storage.initialize();
		} catch (IOException e) {
			throw new IllegalStateException("Cannot initialize client storage for " + storage.layout.gameDirectory(), e);
		}
		try {
			ClientObjectStore.publishOwnership(storage);
		} catch (IOException e) {
			// The receipt is collection bookkeeping: every collection republishes it under the lock first, so a failed
			// publish only makes the next collection refuse to run - it must never cost the game its boot.
			LOGGER.error("Could not publish the client object ownership receipt for {}; content collection stays refused until one publishes: {}", storage.layout.gameDirectory(), e.getMessage(), e);
		}
		OPEN_STORAGE.put(canonicalGameDirectory, new WeakReference<>(storage));
		StorageLeftovers.warnAbout(storage.layout.automodpackDirectory());
		return storage;
	}

	public Path gameDirectory() {
		return layout.gameDirectory();
	}

	public Path gamePath(String logicalPath) {
		return layout.gamePath(logicalPath);
	}

	public Path automodpackDirectory() {
		return layout.automodpackDirectory();
	}

	public Path clientDirectory() {
		return layout.clientDirectory();
	}

	public Path dataDirectory() {
		return layout.dataDirectory();
	}

	public DataRootResolver.Location dataLocation() {
		return dataLocation;
	}

	public Path objectsDirectory() {
		return layout.objectsDirectory();
	}

	public Path objectFile(String sha1) {
		return layout.objectFile(sha1);
	}

	public Path overlaysDirectory() {
		return layout.overlaysDirectory();
	}

	public Path generatedCopiesDirectory() {
		return layout.generatedCopiesDirectory();
	}

	public Path generatedCopiesFile() {
		return layout.generatedCopiesFile();
	}

	public Path legacyGeneratedCopiesFile(String modpackId, String contentToken, String selectionDigest) {
		return layout.legacyGeneratedCopiesFile(modpackId, contentToken, selectionDigest);
	}

	public Path activeDirectory() {
		return layout.activeDirectory();
	}

	public Path activePath(String logicalPath) {
		return layout.activePath(logicalPath);
	}

	public Path root(Root root, String modpackId) {
		return layout.root(root, modpackId);
	}

	public Path rootedPath(Root root, String modpackId, String logicalPath) {
		return layout.rootedPath(root, modpackId, logicalPath);
	}

	public Path legacyIncomingDirectory() {
		return layout.legacyIncomingDirectory();
	}

	public Path legacyBackupDirectory() {
		return layout.legacyBackupDirectory();
	}

	public Path recoveredDirectory() {
		return layout.recoveredDirectory();
	}

	public Path stagingDirectory() {
		return layout.stagingDirectory();
	}

	public Path stateFile() {
		return layout.stateFile();
	}

	public Path transactionFile() {
		return layout.transactionFile();
	}

	public Path projectionRelaunchFile() {
		return layout.projectionRelaunchFile();
	}

	Path projectionRepairBackupsDirectory() {
		return layout.projectionRepairBackupsDirectory();
	}

	public Path projectionStateFile() {
		return layout.projectionStateFile();
	}

	public Path repairJournalFile() {
		return layout.repairJournalFile();
	}

	public Path mutationLockFile() {
		return layout.mutationLockFile();
	}

	public Path selectionFile() {
		return layout.selectionFile();
	}

	public Path stuckTransactionStateFile() {
		return layout.stuckTransactionStateFile();
	}

	public Path restartLoopStateFile() {
		return layout.restartLoopStateFile();
	}

	public Path clientConfigFile() {
		return layout.clientConfigFile();
	}

	public Path selectedModpackFile() {
		return layout.selectedModpackFile();
	}

	public String selectedModpackId() {
		ClientConfigJsons.SelectedModpackFields fields = ConfigTools.read(selectedModpackFile(), ClientConfigJsons.SelectedModpackFields.class).orElse(null);
		if (fields == null || fields.modpackId == null) return "";
		return fields.modpackId;
	}

	public void writeSelectedModpackId(String modpackId) throws IOException {
		ClientConfigJsons.SelectedModpackFields fields = new ClientConfigJsons.SelectedModpackFields();
		fields.modpackId = modpackId == null ? "" : modpackId;
		Files.createDirectories(selectedModpackFile().getParent());
		ConfigTools.writeAtomic(selectedModpackFile(), fields);
	}

	public Path fileCacheDirectory() {
		return layout.fileCacheDirectory();
	}

	public Path modCacheDirectory() {
		return layout.modCacheDirectory();
	}

	public Path platformCacheDirectory() {
		return layout.platformCacheDirectory();
	}

	public Path packsDirectory() {
		return layout.packsDirectory();
	}

	public Path knownHostsFile() {
		return layout.knownHostsFile();
	}

	public Path knownHostsLockFile() {
		return layout.knownHostsLockFile();
	}

	public Path modpackContentTempFile() {
		return layout.modpackContentTempFile();
	}

	public Path historyDirectory() {
		return layout.historyDirectory();
	}

	public Path stateHistoryJournalFile() {
		return layout.stateHistoryJournalFile();
	}

	public Path stateHistoryTreesDirectory() {
		return layout.stateHistoryTreesDirectory();
	}

	public Path stateHistoryTreeFile(String sha1) {
		return layout.stateHistoryTreeFile(sha1);
	}

	public Path historyPackDirectory(String modpackId) {
		return layout.historyPackDirectory(modpackId);
	}

	public Path historyJournalFile(String modpackId) {
		return layout.historyJournalFile(modpackId);
	}

	public Path historyHeadFile(String modpackId) {
		return layout.historyHeadFile(modpackId);
	}

	public Path historyCompactionReceiptFile(String modpackId) {
		return layout.historyCompactionReceiptFile(modpackId);
	}

	public Path journalTempFile() {
		return layout.journalTempFile();
	}

	public Path bootstrapFile() {
		return layout.bootstrapFile();
	}

	public Path modsDirectory() {
		return layout.modsDirectory();
	}

	public Path connectionFile(String modpackId) {
		return layout.connectionFile(modpackId);
	}

	public Path connectionDirectory(String modpackId) {
		return layout.connectionDirectory(modpackId);
	}

	public Path connectionLockFile(String modpackId) {
		return layout.connectionLockFile(modpackId);
	}

	public Path overlayDirectory(String modpackId) {
		return layout.overlayDirectory(modpackId);
	}

	public Path overlayFile(String modpackId, String logicalPath) {
		return layout.overlayFile(modpackId, logicalPath);
	}

	public Path overlayStateFile(String modpackId) {
		return layout.overlayStateFile(modpackId);
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
		FileTrees.createManagedDirectory(clientDirectory(), "client state root");
		FileTrees.createManagedDirectory(objectsDirectory(), "client object store");
		FileTrees.createManagedDirectory(fileCacheDirectory(), "file cache");
		FileTrees.createManagedDirectory(modCacheDirectory(), "mod metadata cache");
		FileTrees.createManagedDirectory(platformCacheDirectory(), "platform cache");
		FileTrees.createManagedDirectory(packsDirectory(), "shared pack state");
		FileTrees.createManagedDirectory(overlaysDirectory(), "client overlays");
		FileTrees.createManagedDirectory(stagingDirectory(), "shared publication staging");
		FileTrees.createManagedDirectory(historyDirectory(), "client journal mirrors");
		FileTrees.createManagedDirectory(layout.stateHistoryDirectory(), "client state history");
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

	private static String requireLogicalPath(String value) {
		return LogicalPath.requireCanonical(value);
	}

}
