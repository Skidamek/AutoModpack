package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.storage.StoragePaths.*;

import java.nio.file.Path;
import java.util.Objects;

import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The physical paths of one client instance, and the checks that keep them inside their roots.
 *
 * <p>
 * Constructed from the resolved data location; every client entry point gets one through
 * {@link ClientStorage}.
 * </p>
 */
public final class ClientLayout {
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
	private final DataRootResolver.Layout dataLayout;

	public ClientLayout(DataRootResolver.Location dataLocation) {
		Objects.requireNonNull(dataLocation, "data location");
		this.gameDirectory = requireDirectoryPath(dataLocation.ownerPath(), "game directory");
		this.automodpackDirectory = this.gameDirectory.resolve(AUTOMODPACK_DIR).normalize();
		this.clientDirectory = this.gameDirectory.resolve(CLIENT_DIR).normalize();
		this.dataDirectory = dataLocation.root();
		this.dataLayout = dataLocation.layout();
		this.objectsDirectory = dataLayout.objectsDirectory();
		this.overlaysDirectory = this.gameDirectory.resolve(CLIENT_OVERLAYS_DIR).normalize();
		this.generatedCopiesDirectory = this.gameDirectory.resolve(CLIENT_GENERATED_COPIES_DIR).normalize();
		this.generatedCopiesFile = this.gameDirectory.resolve(CLIENT_GENERATED_COPIES_FILE).normalize();
		this.activeDirectory = this.gameDirectory.resolve(CLIENT_ACTIVE_DIR).normalize();
		this.legacyIncomingDirectory = this.gameDirectory.resolve(CLIENT_LEGACY_INCOMING_DIR).normalize();
		this.legacyBackupDirectory = this.gameDirectory.resolve(CLIENT_LEGACY_BACKUP_DIR).normalize();
		this.stateFile = this.gameDirectory.resolve(CLIENT_ACTIVE_STATE_FILE).normalize();
		this.transactionFile = this.gameDirectory.resolve(CLIENT_TRANSACTION_FILE).normalize();
		this.projectionStateFile = ProjectionState.file(this.gameDirectory);
		this.projectionRelaunchFile = this.gameDirectory.resolve(CLIENT_PROJECTION_RELAUNCH_FILE).normalize();
		this.projectionRepairBackupsDirectory = this.clientDirectory.resolve(ProjectionRepairBackups.DIRECTORY).normalize();
		this.repairJournalFile = this.gameDirectory.resolve(CLIENT_REPAIR_FILE).normalize();
		this.mutationLockFile = this.gameDirectory.resolve(CLIENT_MUTATION_LOCK_FILE).normalize();
		this.selectionFile = this.gameDirectory.resolve(CLIENT_SELECTION_FILE).normalize();
		this.relayConsentFile = this.gameDirectory.resolve(CLIENT_RELAY_FILE).normalize();
		this.restartLoopStateFile = this.gameDirectory.resolve(CLIENT_RESTART_LOOP_STATE_FILE).normalize();
		this.stuckTransactionStateFile = this.gameDirectory.resolve(CLIENT_STUCK_TRANSACTION_STATE_FILE).normalize();
		this.clientConfigFile = this.gameDirectory.resolve(CLIENT_CONFIG_FILE).normalize();
		this.selectedModpackFile = this.gameDirectory.resolve(CLIENT_SELECTED_FILE).normalize();
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
		return dataLayout.stagingDirectory();
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

	public Path selectedModpackFile() {
		return selectedModpackFile;
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

	public Path stateHistoryDirectory() {
		return stateHistoryDirectory;
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

	private void validateLayout() {
		validateWithin(gameDirectory, automodpackDirectory);
		validateWithin(automodpackDirectory, clientDirectory, clientConfigFile, bootstrapFile, gameDirectory.resolve(RECOVERED_DIR));
		validateWithin(clientDirectory, overlaysDirectory, generatedCopiesDirectory, generatedCopiesFile, activeDirectory, legacyIncomingDirectory, legacyBackupDirectory,
				historyDirectory, stateHistoryDirectory, stateHistoryTreesDirectory(), stateFile, transactionFile, projectionStateFile, projectionRelaunchFile, projectionRepairBackupsDirectory,
				repairJournalFile, mutationLockFile, selectionFile, relayConsentFile, selectedModpackFile,
				restartLoopStateFile,
				stuckTransactionStateFile,
				modpackContentTempFile,
				journalTempFile);
		validateWithin(dataDirectory, objectsDirectory, fileCacheDirectory, modCacheDirectory, platformCacheDirectory, packsDirectory, stagingDirectory(), knownHostsFile, knownHostsLockFile);
	}

	private static void validateWithin(Path parent, Path... children) {
		for (Path child : children)
			if (!child.startsWith(parent)) throw new IllegalArgumentException("Client storage path escaped " + parent + ": " + child);
	}

	static Path requireDirectoryPath(Path path, String description) {
		return Objects.requireNonNull(path, description).toAbsolutePath().normalize();
	}

	private static Path resolveLogical(Path root, String logicalPath) {
		return LogicalPath.resolve(root, logicalPath);
	}

}
