package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.clientActiveDir;
import static pl.skidam.automodpack_core.Constants.clientActiveStateFile;
import static pl.skidam.automodpack_core.Constants.clientBackupDir;
import static pl.skidam.automodpack_core.Constants.clientBaselinesDir;
import static pl.skidam.automodpack_core.Constants.clientConfigFile;
import static pl.skidam.automodpack_core.Constants.clientContentTempFile;
import static pl.skidam.automodpack_core.Constants.clientDir;
import static pl.skidam.automodpack_core.Constants.clientHelperDir;
import static pl.skidam.automodpack_core.Constants.clientIncomingDir;
import static pl.skidam.automodpack_core.Constants.clientOverlaysDir;
import static pl.skidam.automodpack_core.Constants.clientQuarantineDir;
import static pl.skidam.automodpack_core.Constants.clientRecordsDir;
import static pl.skidam.automodpack_core.Constants.clientRecoveryDir;
import static pl.skidam.automodpack_core.Constants.clientRestartLoopStateFile;
import static pl.skidam.automodpack_core.Constants.clientSelectionFile;
import static pl.skidam.automodpack_core.Constants.clientTransactionFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

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
	private static final Pattern DIGEST = Pattern.compile("[0-9a-fA-F]{40}");

	private final Path gameDirectory;
	private final Path automodpackDirectory;
	private final Path clientDirectory;
	private final Path dataDirectory;
	private final boolean sharedDataDirectory;
	private final Path objectsDirectory;
	private final Path recordsDirectory;
	private final Path overlaysDirectory;
	private final Path baselinesDirectory;
	private final Path activeDirectory;
	private final Path incomingDirectory;
	private final Path backupDirectory;
	private final Path stateFile;
	private final Path transactionFile;
	private final Path selectionFile;
	private final Path restartLoopStateFile;
	private final Path clientConfigFile;
	private final Path modpackContentTempFile;
	private final Path helperDirectory;
	private final Path recoveryDirectory;
	private final Path quarantineDirectory;
	private final Path bootstrapFile;
	private final Path fileMetadataDirectory;
	private final Path modMetadataDirectory;
	private final Path packsDirectory;
	private final Path knownHostsFile;
	private final Path knownHostsLockFile;

	public ClientStorage(Path gameDirectory) {
		this.gameDirectory = requireDirectoryPath(gameDirectory, "game directory");
		this.automodpackDirectory = this.gameDirectory.resolve(automodpackDirName()).normalize();
		this.clientDirectory = this.gameDirectory.resolve(clientDir).normalize();
		DataRootResolver.Location dataLocation = DataRootResolver.resolve(this.gameDirectory);
		this.dataDirectory = dataLocation.root();
		this.sharedDataDirectory = dataLocation.shared();
		this.objectsDirectory = dataDirectory.resolve("objects").normalize();
		this.recordsDirectory = this.clientDirectory.resolve(clientRecordsDir.getFileName()).normalize();
		this.overlaysDirectory = this.clientDirectory.resolve(clientOverlaysDir.getFileName()).normalize();
		this.baselinesDirectory = this.clientDirectory.resolve(clientBaselinesDir.getFileName()).normalize();
		this.activeDirectory = this.clientDirectory.resolve(clientActiveDir.getFileName()).normalize();
		this.incomingDirectory = this.clientDirectory.resolve(clientIncomingDir.getFileName()).normalize();
		this.backupDirectory = this.clientDirectory.resolve(clientBackupDir.getFileName()).normalize();
		this.stateFile = this.clientDirectory.resolve(clientActiveStateFile.getFileName()).normalize();
		this.transactionFile = this.clientDirectory.resolve(clientTransactionFile.getFileName()).normalize();
		this.selectionFile = this.clientDirectory.resolve(clientSelectionFile.getFileName()).normalize();
		this.restartLoopStateFile = this.clientDirectory.resolve(clientRestartLoopStateFile.getFileName()).normalize();
		this.clientConfigFile = this.gameDirectory.resolve(Constants.clientConfigFile).normalize();
		this.modpackContentTempFile = this.clientDirectory.resolve(clientContentTempFile.getFileName()).normalize();
		this.helperDirectory = this.clientDirectory.resolve(clientHelperDir.getFileName()).normalize();
		this.bootstrapFile = this.gameDirectory.resolve(Constants.bootstrapFile).normalize();
		this.recoveryDirectory = this.clientDirectory.resolve(clientRecoveryDir.getFileName()).normalize();
		this.quarantineDirectory = this.clientDirectory.resolve(clientQuarantineDir.getFileName()).normalize();
		this.fileMetadataDirectory = dataDirectory.resolve("file-metadata").normalize();
		this.modMetadataDirectory = dataDirectory.resolve("mod-metadata").normalize();
		this.packsDirectory = dataDirectory.resolve("packs").normalize();
		this.knownHostsFile = dataDirectory.resolve("known-hosts.json").normalize();
		this.knownHostsLockFile = dataDirectory.resolve("known-hosts.json.lock").normalize();
		validateLayout();
	}

	public static ClientStorage fromGameDirectory(Path gameDirectory) {
		return new ClientStorage(gameDirectory);
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

	public Path recoveryDirectory() {
		return recoveryDirectory;
	}

	public Path quarantineDirectory() {
		return quarantineDirectory;
	}

	public Path quarantinePackDirectory(String modpackId) {
		return quarantineDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
	}

	public Path quarantinePayload(String modpackId, String conflictId) {
		if (conflictId == null || !conflictId.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid conflict ID");
		Path root = quarantinePackDirectory(modpackId);
		Path payload = root.resolve("conflicts").resolve(conflictId).resolve("payload").normalize();
		if (!payload.startsWith(root)) throw new IllegalArgumentException("Quarantine path escaped its modpack root");
		return payload;
	}

	public Path quarantineManifest(String modpackId) {
		return quarantinePackDirectory(modpackId).resolve("manifest.json").normalize();
	}

	public Path bootstrapFile() {
		return bootstrapFile;
	}

	public Path modsDirectory() {
		return gamePath("mods");
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

	public Jsons.ClientOverlayFields readOverlayState(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		Path stateFile = overlayStateFile(normalizedModpackId);
		if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
			Jsons.ClientOverlayFields empty = new Jsons.ClientOverlayFields();
			empty.modpackId = normalizedModpackId;
			empty.deletedPaths = List.of();
			return empty;
		}
		if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client overlay state is not a regular file: " + stateFile);
		Jsons.ClientOverlayFields state = ConfigTools.read(stateFile, Jsons.ClientOverlayFields.class)
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
		Path stateFile = overlayStateFile(normalizedModpackId);
		if (canonical.isEmpty()) {
			Files.deleteIfExists(stateFile);
			return;
		}
		Jsons.ClientOverlayFields state = new Jsons.ClientOverlayFields();
		state.modpackId = normalizedModpackId;
		state.deletedPaths = List.copyOf(canonical);
		ConfigTools.writeAtomic(stateFile, state);
	}

	public void clearOverlay(String modpackId) throws IOException {
		SmartFileUtils.deleteTree(overlayDirectory(modpackId));
		Files.deleteIfExists(overlayStateFile(modpackId));
	}

	public Path baselineFile(String modpackId) {
		return baselinesDirectory.resolve(ModpackId.requireValid(modpackId)).resolve("baseline.json").normalize();
	}

	public Path incomingTransactionDirectory(String transactionId) {
		return incomingDirectory.resolve(requireTransactionId(transactionId)).normalize();
	}

	public Path backupTransactionDirectory(String transactionId) {
		return backupDirectory.resolve(requireTransactionId(transactionId)).normalize();
	}

	public Path recoveryDirectory(String modpackId) {
		return recoveryDirectory.resolve(ModpackId.requireValid(modpackId)).normalize();
	}

	public String overlayDigest(String modpackId) throws IOException {
		Path root = overlayDirectory(modpackId);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			for (String deletedPath : readOverlayState(modpackId).deletedPaths) digest.update(("D\0" + deletedPath + "\n").getBytes(StandardCharsets.UTF_8));
			if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return HexFormat.of().formatHex(digest.digest());
			if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client overlay root is not a directory: " + root);
			try (Stream<Path> paths = Files.walk(root)) {
				for (Path path : paths.filter(candidate -> !candidate.equals(root)).sorted().toList()) {
					if (Files.isSymbolicLink(path)) throw new IOException("Client overlay contains a symbolic link: " + path);
					if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
					String relative = LogicalPath.normalize(root.relativize(path).toString());
					String hash = HashUtils.getHash(path);
					if (hash == null) throw new IOException("Cannot hash client overlay file: " + path);
					digest.update((relative + "\0" + Files.size(path) + "\0" + hash.toLowerCase(Locale.ROOT) + "\n").getBytes(StandardCharsets.UTF_8));
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-1 is required by the client protocol", e);
		}
	}

	public void ensureRoots() throws IOException {
		ensureDirectory(clientDirectory, "client state root");
		ensureDirectory(objectsDirectory, "client object store");
		ensureDirectory(dataDirectory.resolve("file-metadata"), "file metadata cache");
		ensureDirectory(dataDirectory.resolve("mod-metadata"), "mod metadata cache");
		ensureDirectory(packsDirectory, "shared pack state");
		ensureDirectory(recordsDirectory, "client generation records");
		ensureDirectory(overlaysDirectory, "client overlays");
		ensureDirectory(baselinesDirectory, "client baselines");
		ensureDirectory(incomingDirectory, "client transaction incoming root");
		ensureDirectory(backupDirectory, "client transaction backup root");
		ensureDirectory(helperDirectory, "client update helper");
		ensureDirectory(quarantineDirectory, "client quarantine root");
	}

	public Jsons.ClientGenerationStateFields readActiveState() throws IOException {
		if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return null;
		if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Client active state is not a regular file");
		Jsons.ClientGenerationStateFields state = ConfigTools.read(stateFile, Jsons.ClientGenerationStateFields.class)
				.orElseThrow(() -> new IOException("Client active state is empty"));
		if (!ModpackId.isValid(state.modpackId) || !DIGEST.matcher(state.generationId).matches() || !"ACTIVE".equals(state.status))
			throw new IOException("Client active state identity is invalid");
		return state;
	}

	public void writeActiveState(String modpackId, String generationId) throws IOException {
		Jsons.ClientGenerationStateFields state = new Jsons.ClientGenerationStateFields();
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
		validateWithin(clientDirectory, recordsDirectory, overlaysDirectory, baselinesDirectory, activeDirectory, incomingDirectory, backupDirectory, recoveryDirectory, quarantineDirectory,
				stateFile, transactionFile, selectionFile, restartLoopStateFile, modpackContentTempFile, helperDirectory);
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
		if (value == null || !DIGEST.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + description);
		return value.toLowerCase(Locale.ROOT);
	}

	private static String requireTransactionId(String value) {
		try {
			return UUID.fromString(value).toString();
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid transaction UUID", e);
		}
	}

	private static String requireLogicalPath(String value) {
		return LogicalPath.requireCanonical(value);
	}

	private static Path resolveLogical(Path root, String logicalPath) {
		Path resolved = root.resolve(requireLogicalPath(logicalPath)).normalize();
		if (!resolved.startsWith(root)) throw new IllegalArgumentException("Logical path escapes its root: " + logicalPath);
		return resolved;
	}

	private static String automodpackDirName() {
		return "automodpack";
	}

	private static void ensureDirectory(Path directory, String description) throws IOException {
		if (Files.isSymbolicLink(directory)) throw new IOException("Managed " + description + " cannot be a symbolic link: " + directory);
		Files.createDirectories(directory);
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Managed " + description + " is not a directory: " + directory);
	}
}
