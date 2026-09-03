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
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
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
	private final Path platformMetadataDirectory;
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
		this.recordsDirectory = this.gameDirectory.resolve(CLIENT_RECORDS_DIR).normalize();
		this.overlaysDirectory = this.gameDirectory.resolve(CLIENT_OVERLAYS_DIR).normalize();
		this.baselinesDirectory = this.gameDirectory.resolve(CLIENT_BASELINES_DIR).normalize();
		this.generatedCopiesDirectory = this.gameDirectory.resolve(CLIENT_GENERATED_COPIES_DIR).normalize();
		this.activeDirectory = this.gameDirectory.resolve(CLIENT_ACTIVE_DIR).normalize();
		this.incomingDirectory = this.gameDirectory.resolve(CLIENT_INCOMING_DIR).normalize();
		this.backupDirectory = this.gameDirectory.resolve(CLIENT_BACKUP_DIR).normalize();
		this.stateFile = this.gameDirectory.resolve(CLIENT_ACTIVE_STATE_FILE).normalize();
		this.transactionFile = this.gameDirectory.resolve(CLIENT_TRANSACTION_FILE).normalize();
		this.repairJournalFile = this.gameDirectory.resolve(CLIENT_REPAIR_FILE).normalize();
		this.compactionJournalFile = this.gameDirectory.resolve(CLIENT_COMPACTION_FILE).normalize();
		this.mutationLockFile = this.gameDirectory.resolve(CLIENT_MUTATION_LOCK_FILE).normalize();
		this.selectionFile = this.gameDirectory.resolve(CLIENT_SELECTION_FILE).normalize();
		this.restartLoopStateFile = this.gameDirectory.resolve(CLIENT_RESTART_LOOP_STATE_FILE).normalize();
		this.clientConfigFile = this.gameDirectory.resolve(CLIENT_CONFIG_FILE).normalize();
		this.modpackContentTempFile = this.gameDirectory.resolve(CLIENT_CONTENT_TEMP_FILE).normalize();
		this.helperDirectory = this.gameDirectory.resolve(CLIENT_HELPER_DIR).normalize();
		this.helperLeaseFile = this.gameDirectory.resolve(CLIENT_HELPER_LEASE_FILE).normalize();
		this.preservationDirectory = this.gameDirectory.resolve(CLIENT_PRESERVATION_DIR).normalize();
		this.bootstrapFile = this.gameDirectory.resolve(BOOTSTRAP_FILE).normalize();
		this.fileMetadataDirectory = dataLayout.fileMetadataDirectory();
		this.modMetadataDirectory = dataLayout.modMetadataDirectory();
		this.platformMetadataDirectory = dataLayout.platformMetadataDirectory();
		this.packsDirectory = dataLayout.packsDirectory();
		this.knownHostsFile = dataLayout.knownHostsFile();
		this.knownHostsLockFile = dataLayout.knownHostsLockFile();
		validateLayout();
	}

	public static synchronized ClientStorage open(Path gameDirectory) {
		DataRootResolver.Location dataLocation = DataRootResolver.resolve(requireDirectoryPath(gameDirectory, "game directory"));
		Path canonicalGameDirectory = dataLocation.ownerPath();
		WeakReference<ClientStorage> reference = OPEN_STORAGE.get(canonicalGameDirectory);
		ClientStorage existing = reference == null ? null : reference.get();
		if (existing != null) return existing;
		OPEN_STORAGE.entrySet().removeIf(entry -> entry.getValue().get() == null);
		ClientStorage storage = new ClientStorage(dataLocation);
		try {
			storage.initialize();
			new ClientGenerationStore(storage).recoverCompaction();
			ClientObjectStore.publishOwnership(storage);
			OPEN_STORAGE.put(canonicalGameDirectory, new WeakReference<>(storage));
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

	public Path objectsDirectory() {
		return objectsDirectory;
	}

	public Path objectFile(String sha1) {
		return DataRootResolver.objectFile(objectsDirectory, sha1);
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

	public Path generatedCopiesFile(String modpackId, String contentToken, String selectionDigest) {
		Path packRoot = generatedCopiesDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
		Path generationRoot = packRoot.resolve(requireDigest(contentToken, "generation ID")).normalize();
		Path file = generationRoot.resolve(requireDigest(selectionDigest, "generated-copy selection digest") + ".json").normalize();
		if (!file.startsWith(generationRoot)) throw new IllegalArgumentException("Generated-copy state escaped its generation root");
		return file;
	}

	public Path generatedCopiesGenerationDirectory(String modpackId, String contentToken) {
		Path root = generatedCopiesDirectory.resolve(ModpackId.requireValid(modpackId)).resolve(requireDigest(contentToken, "generation ID")).normalize();
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

	public Path platformMetadataDirectory() {
		return platformMetadataDirectory;
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

	public Path restoredClaimDirectory(String modpackId, String contentToken, String claimId) {
		String generation = contentToken == null || contentToken.isEmpty() ? "unversioned" : requireDigest(contentToken, "generation ID");
		Path root = gameDirectory.resolve(RECOVERED_DIR).resolve(ModpackId.requireValid(modpackId)).resolve(generation).normalize();
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

	public Path generationDirectory(String contentToken) {
		return recordsDirectory.resolve(requireDigest(contentToken, "generation ID")).normalize();
	}

	public Path generationManifest(String contentToken) {
		return generationDirectory(contentToken).resolve("manifest.json");
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
		return gameDirectory.resolve(CLIENT_INCOMING_PROJECTION_DIR).normalize();
	}

	public Path backupProjectionDirectory() {
		return gameDirectory.resolve(CLIENT_BACKUP_PROJECTION_DIR).normalize();
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
		FileTrees.createManagedDirectory(platformMetadataDirectory, "platform metadata cache");
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
		if (!ModpackId.isValid(state.modpackId) || !HashUtils.isSha1(state.contentToken) || !"ACTIVE".equals(state.status))
			throw new IOException("Client active state identity is invalid");
		return state;
	}

	public void writeActiveState(String modpackId, String contentToken) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = new ClientStorageJsons.ClientGenerationStateFields();
		state.modpackId = ModpackId.requireValid(modpackId);
		state.contentToken = requireDigest(contentToken, "generation ID");
		Files.createDirectories(stateFile.getParent());
		ConfigTools.writeAtomic(stateFile, state);
	}

	public void clearActiveState() throws IOException {
		Files.deleteIfExists(stateFile);
	}

	private void validateLayout() {
		validateWithin(gameDirectory, automodpackDirectory);
		validateWithin(automodpackDirectory, clientDirectory, clientConfigFile, bootstrapFile, gameDirectory.resolve(RECOVERED_DIR));
		validateWithin(clientDirectory, recordsDirectory, overlaysDirectory, baselinesDirectory, generatedCopiesDirectory, activeDirectory, incomingDirectory, backupDirectory, preservationDirectory,
				stateFile, transactionFile, repairJournalFile, compactionJournalFile, mutationLockFile, selectionFile, restartLoopStateFile, modpackContentTempFile, helperDirectory, helperLeaseFile,
				incomingProjectionDirectory(), backupProjectionDirectory());
		validateWithin(dataDirectory, objectsDirectory, fileMetadataDirectory, modMetadataDirectory, platformMetadataDirectory, packsDirectory, knownHostsFile, knownHostsLockFile);
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
