package pl.skidam.automodpack_core.update;

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

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/**
 * The only authority for client-side AutoModpack paths.
 *
 * <p>
 * Every client entry point, including the detached helper, constructs this
 * object from the game directory. The active projection is deliberately a
 * fixed path; generation IDs identify immutable records, not directories
 * exposed to the game.
 * </p>
 */
public final class ClientStorage {
	private static final Map<Path, WeakReference<ClientStorage>> OPEN_STORAGE = new HashMap<>();
	private final Path gameDirectory;
	private final Path automodpackDirectory;
	private final Path clientDirectory;
	private final DataRootResolver.Location dataLocation;
	private final Path dataDirectory;
	private final boolean sharedDataDirectory;
	private final Path objectsDirectory;
	private final Path recordsDirectory;
	private final Path overlaysDirectory;
	private final Path baselinesDirectory;
	private final Path generatedCopiesDirectory;
	private final Path activeDirectory;
	private final Path incomingDirectory;
	private final Path backupDirectory;
	private final Path stateFile;
	private final Path transactionFile;
	private final Path repairJournalFile;
	private final Path compactionJournalFile;
	private final Path mutationLockFile;
	private final Path selectionFile;
	private final Path restartLoopStateFile;
	private final Path clientConfigFile;
	private final Path modpackContentTempFile;
	private final Path helperDirectory;
	private final Path helperLeaseFile;
	private final Path preservationDirectory;
	private final Path bootstrapFile;
	private final Path fileMetadataDirectory;
	private final Path modMetadataDirectory;
	private final Path packsDirectory;
	private final Path knownHostsFile;
	private final Path knownHostsLockFile;

	private ClientStorage(Path gameDirectory) {
		this.gameDirectory = requireDirectoryPath(gameDirectory, "game directory");
		this.automodpackDirectory = this.gameDirectory.resolve(AUTOMODPACK_DIR).normalize();
		this.clientDirectory = this.gameDirectory.resolve(CLIENT_DIR).normalize();
		this.dataLocation = DataRootResolver.resolve(this.gameDirectory);
		this.dataDirectory = dataLocation.root();
		this.sharedDataDirectory = dataLocation.shared();
		DataRootResolver.Layout dataLayout = dataLocation.layout();
		this.objectsDirectory = dataLayout.objectsDirectory();
		this.recordsDirectory = this.clientDirectory.resolve(CLIENT_RECORDS_DIR.getFileName()).normalize();
		this.overlaysDirectory = this.clientDirectory.resolve(CLIENT_OVERLAYS_DIR.getFileName()).normalize();
		this.baselinesDirectory = this.clientDirectory.resolve(CLIENT_BASELINES_DIR.getFileName()).normalize();
		this.generatedCopiesDirectory = this.clientDirectory.resolve(CLIENT_GENERATED_COPIES_DIR.getFileName()).normalize();
		this.activeDirectory = this.clientDirectory.resolve(CLIENT_ACTIVE_DIR.getFileName()).normalize();
		this.incomingDirectory = this.clientDirectory.resolve(CLIENT_INCOMING_DIR.getFileName()).normalize();
		this.backupDirectory = this.clientDirectory.resolve(CLIENT_BACKUP_DIR.getFileName()).normalize();
		this.stateFile = this.clientDirectory.resolve(CLIENT_ACTIVE_STATE_FILE.getFileName()).normalize();
		this.transactionFile = this.clientDirectory.resolve(CLIENT_TRANSACTION_FILE.getFileName()).normalize();
		this.repairJournalFile = this.clientDirectory.resolve("repair.json").normalize();
		this.compactionJournalFile = this.clientDirectory.resolve("compaction.json").normalize();
		this.mutationLockFile = this.clientDirectory.resolve("mutation.lock").normalize();
		this.selectionFile = this.clientDirectory.resolve(CLIENT_SELECTION_FILE.getFileName()).normalize();
		this.restartLoopStateFile = this.clientDirectory.resolve(CLIENT_RESTART_LOOP_STATE_FILE.getFileName()).normalize();
		this.clientConfigFile = this.gameDirectory.resolve(CLIENT_CONFIG_FILE).normalize();
		this.modpackContentTempFile = this.clientDirectory.resolve(CLIENT_CONTENT_TEMP_FILE.getFileName()).normalize();
		this.helperDirectory = this.clientDirectory.resolve(CLIENT_HELPER_DIR.getFileName()).normalize();
		this.helperLeaseFile = this.clientDirectory.resolve(CLIENT_HELPER_DIR.getFileName()).resolve(CLIENT_HELPER_LEASE_FILE.getFileName()).normalize();
		this.preservationDirectory = this.clientDirectory.resolve(CLIENT_PRESERVATION_DIR.getFileName()).normalize();
		this.bootstrapFile = this.gameDirectory.resolve(BOOTSTRAP_FILE).normalize();
		this.fileMetadataDirectory = dataLayout.fileMetadataDirectory();
		this.modMetadataDirectory = dataLayout.modMetadataDirectory();
		this.packsDirectory = dataLayout.packsDirectory();
		this.knownHostsFile = dataLayout.knownHostsFile();
		this.knownHostsLockFile = dataLayout.knownHostsLockFile();
		validateLayout();
	}

	public static synchronized ClientStorage open(Path gameDirectory) {
		Path normalized = requireDirectoryPath(gameDirectory, "game directory");
		WeakReference<ClientStorage> reference = OPEN_STORAGE.get(normalized);
		ClientStorage existing = reference == null ? null : reference.get();
		if (existing != null) return existing;
		OPEN_STORAGE.entrySet().removeIf(entry -> entry.getValue().get() == null);
		ClientStorage storage = new ClientStorage(normalized);
		try {
			storage.initialize();
			new ClientGenerationStore(storage).recoverCompaction();
			if (storage.sharedDataDirectory()) ClientObjectStore.publishOwnership(storage);
			OPEN_STORAGE.put(normalized, new WeakReference<>(storage));
			return storage;
		} catch (IOException e) {
			throw new IllegalStateException("Cannot initialize client storage for " + storage.gameDirectory, e);
		}
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

	public boolean sharedDataDirectory() {
		return sharedDataDirectory;
	}

	public Path objectsDirectory() {
		return objectsDirectory;
	}

	public Path recordsDirectory() {
		return recordsDirectory;
	}

	public Path overlaysDirectory() {
		return overlaysDirectory;
	}

	public Path baselinesDirectory() {
		return baselinesDirectory;
	}

	public Path generatedCopiesDirectory() {
		return generatedCopiesDirectory;
	}

	public Path generatedCopiesFile(String modpackId, String generationId, String selectionDigest) {
		Path packRoot = generatedCopiesDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
		Path generationRoot = packRoot.resolve(requireDigest(generationId, "generation ID")).normalize();
		Path file = generationRoot.resolve(requireDigest(selectionDigest, "generated-copy selection digest") + ".json").normalize();
		if (!file.startsWith(generationRoot)) throw new IllegalArgumentException("Generated-copy state escaped its generation root");
		return file;
	}

	public Path generatedCopiesGenerationDirectory(String modpackId, String generationId) {
		Path root = generatedCopiesDirectory.resolve(ModpackId.requireValid(modpackId)).resolve(requireDigest(generationId, "generation ID")).normalize();
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

	public Path incomingDirectory() {
		return incomingDirectory;
	}

	public Path backupDirectory() {
		return backupDirectory;
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

	public Path compactionJournalFile() {
		return compactionJournalFile;
	}

	public Path mutationLockFile() {
		return mutationLockFile;
	}

	public Path selectionFile() {
		return selectionFile;
	}

	public Path restartLoopStateFile() {
		return restartLoopStateFile;
	}

	public Path clientConfigFile() {
		return clientConfigFile;
	}

	public Path fileMetadataDirectory() {
		return fileMetadataDirectory;
	}

	public Path modMetadataDirectory() {
		return modMetadataDirectory;
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

	public Path helperDirectory() {
		return helperDirectory;
	}

	public Path helperLeaseFile() {
		return helperLeaseFile;
	}

	public Path preservationDirectory() {
		return preservationDirectory;
	}

	public Path preservationPackDirectory(String modpackId) {
		return preservationDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
	}

	public Path preservationManifest(String modpackId) {
		return preservationPackDirectory(modpackId).resolve("claims.json").normalize();
	}

	public Path restoredClaimDirectory(String modpackId, String generationId, String claimId) {
		String generation = generationId == null || generationId.isEmpty() ? "unversioned" : requireDigest(generationId, "generation ID");
		Path root = automodpackDirectory.resolve("restored").resolve(ModpackId.requireValid(modpackId)).resolve(generation).normalize();
		Path claim = root.resolve(requireDigest(claimId, "preservation claim ID")).normalize();
		if (!claim.startsWith(root)) throw new IllegalArgumentException("Restored copy path escaped its modpack root");
		return claim;
	}

	public Path bootstrapFile() {
		return bootstrapFile;
	}

	public Path modsDirectory() {
		return gamePath(ModpackPathPolicy.MODS_ROOT);
	}

	public Path generationDirectory(String generationId) {
		return recordsDirectory.resolve(requireDigest(generationId, "generation ID")).normalize();
	}

	public Path generationManifest(String generationId) {
		return generationDirectory(generationId).resolve("manifest.json");
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

	public ClientStorageJsons.ClientOverlayFields readOverlayState(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		Path stateFile = overlayStateFile(normalizedModpackId);
		if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
			ClientStorageJsons.ClientOverlayFields empty = new ClientStorageJsons.ClientOverlayFields();
			empty.modpackId = normalizedModpackId;
			empty.deletedPaths = List.of();
			return empty;
		}
		if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client overlay state is not a regular file: " + stateFile);
		ClientStorageJsons.ClientOverlayFields state = ConfigTools.read(stateFile, ClientStorageJsons.ClientOverlayFields.class)
				.orElseThrow(() -> new IOException("Client overlay state is empty: " + stateFile));
		if (!normalizedModpackId.equals(state.modpackId) || state.deletedPaths == null) throw new IOException("Client overlay state identity is invalid: " + stateFile);
		List<String> canonical = state.deletedPaths.stream().map(ClientStorage::requireLogicalPath).distinct().sorted().toList();
		if (!canonical.equals(state.deletedPaths)) throw new IOException("Client overlay tombstones are not canonical: " + stateFile);
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

	public Path baselineFile(String modpackId) {
		return baselinesDirectory.resolve(ModpackId.requireValid(modpackId)).resolve("baseline.json").normalize();
	}

	public Path incomingProjectionDirectory() {
		return incomingDirectory.resolve("projection").normalize();
	}

	public Path backupProjectionDirectory() {
		return backupDirectory.resolve("projection").normalize();
	}

	public String overlayDigest(String modpackId) throws IOException {
		return ClientOverlaySnapshot.capture(this, modpackId, null).digest();
	}

	public ClientOverlaySnapshot overlaySnapshot(String modpackId, FileMetadataCache cache) throws IOException {
		return ClientOverlaySnapshot.capture(this, modpackId, cache);
	}

	/** Creates the complete client storage layout during application bootstrap. */
	private void initialize() throws IOException {
		FileTrees.createManagedDirectory(clientDirectory, "client state root");
		FileTrees.createManagedDirectory(objectsDirectory, "client object store");
		FileTrees.createManagedDirectory(fileMetadataDirectory, "file metadata cache");
		FileTrees.createManagedDirectory(modMetadataDirectory, "mod metadata cache");
		FileTrees.createManagedDirectory(packsDirectory, "shared pack state");
		FileTrees.createManagedDirectory(recordsDirectory, "client generation records");
		FileTrees.createManagedDirectory(overlaysDirectory, "client overlays");
		FileTrees.createManagedDirectory(baselinesDirectory, "client baselines");
		FileTrees.createManagedDirectory(generatedCopiesDirectory, "client generated-copy state");
		FileTrees.createManagedDirectory(incomingDirectory, "client incoming staging root");
		FileTrees.createManagedDirectory(backupDirectory, "client projection backup root");
		FileTrees.createManagedDirectory(helperDirectory, "client update helper");
		FileTrees.createManagedDirectory(preservationDirectory, "client preservation root");
	}

	public ClientStorageJsons.ClientGenerationStateFields readActiveState() throws IOException {
		if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return null;
		if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Client active state is not a regular file");
		ClientStorageJsons.ClientGenerationStateFields state = ConfigTools.read(stateFile, ClientStorageJsons.ClientGenerationStateFields.class)
				.orElseThrow(() -> new IOException("Client active state is empty"));
		if (!ModpackId.isValid(state.modpackId) || !HashUtils.isSha1(state.generationId) || !"ACTIVE".equals(state.status))
			throw new IOException("Client active state identity is invalid");
		return state;
	}

	public void writeActiveState(String modpackId, String generationId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = new ClientStorageJsons.ClientGenerationStateFields();
		state.modpackId = ModpackId.requireValid(modpackId);
		state.generationId = requireDigest(generationId, "generation ID");
		Files.createDirectories(stateFile.getParent());
		ConfigTools.writeAtomic(stateFile, state);
	}

	public void clearActiveState() throws IOException {
		Files.deleteIfExists(stateFile);
	}

	private void validateLayout() {
		validateWithin(gameDirectory, automodpackDirectory);
		validateWithin(automodpackDirectory, clientDirectory, clientConfigFile);
		validateWithin(gameDirectory, bootstrapFile);
		validateWithin(clientDirectory, recordsDirectory, overlaysDirectory, baselinesDirectory, generatedCopiesDirectory, activeDirectory, incomingDirectory, backupDirectory, preservationDirectory,
				stateFile, transactionFile, repairJournalFile, selectionFile, restartLoopStateFile, modpackContentTempFile, helperDirectory, helperLeaseFile);
		validateWithin(dataDirectory, objectsDirectory, fileMetadataDirectory, modMetadataDirectory, packsDirectory, knownHostsFile, knownHostsLockFile);
	}

	private static void validateWithin(Path parent, Path... children) {
		for (Path child : children)
			if (!child.startsWith(parent)) throw new IllegalArgumentException("Client storage path escaped " + parent + ": " + child);
	}

	private static Path requireDirectoryPath(Path path, String description) {
		return Objects.requireNonNull(path, description).toAbsolutePath().normalize();
	}

	private static String requireDigest(String value, String description) {
		if (!HashUtils.isSha1(value)) throw new IllegalArgumentException("Invalid " + description);
		return HashUtils.normalizeSha1(value);
	}

	private static String requireLogicalPath(String value) {
		return LogicalPath.requireCanonical(value);
	}

	private static Path resolveLogical(Path root, String logicalPath) {
		return LogicalPath.resolve(root, logicalPath);
	}

}
