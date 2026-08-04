package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.cacheDir;
import static pl.skidam.automodpack_core.Constants.clientDummyFilesFile;
import static pl.skidam.automodpack_core.Constants.clientGenerationActiveDir;
import static pl.skidam.automodpack_core.Constants.clientGenerationBackupDir;
import static pl.skidam.automodpack_core.Constants.clientGenerationBaselinesDir;
import static pl.skidam.automodpack_core.Constants.clientGenerationIncomingDir;
import static pl.skidam.automodpack_core.Constants.clientGenerationObjectsDir;
import static pl.skidam.automodpack_core.Constants.clientGenerationOverlaysDir;
import static pl.skidam.automodpack_core.Constants.clientGenerationRecordsDir;
import static pl.skidam.automodpack_core.Constants.clientGenerationStateFile;
import static pl.skidam.automodpack_core.Constants.clientGenerationsDir;
import static pl.skidam.automodpack_core.Constants.clientRestartLoopStateFile;
import static pl.skidam.automodpack_core.Constants.clientSelectionFile;
import static pl.skidam.automodpack_core.Constants.hashCacheDBFile;
import static pl.skidam.automodpack_core.Constants.helperDir;
import static pl.skidam.automodpack_core.Constants.modCacheDBFile;
import static pl.skidam.automodpack_core.Constants.privateDir;
import static pl.skidam.automodpack_core.Constants.recoveryDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.HashUtils;

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
	private final Path generationsDirectory;
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
	private final Path dummyFilesFile;
	private final Path restartLoopStateFile;
	private final Path clientConfigFile;
	private final Path clientSecretsFile;
	private final Path cacheDirectory;
	private final Path hashCacheFile;
	private final Path modCacheFile;
	private final Path modpackContentTempFile;
	private final Path helperDirectory;
	private final Path knownHostsFile;
	private final Path knownHostsBootstrapFile;
	private final Path recoveryDirectory;
	private final Path privateDirectory;

	public ClientStorage(Path gameDirectory) {
		this.gameDirectory = requireDirectoryPath(gameDirectory, "game directory");
		this.automodpackDirectory = this.gameDirectory.resolve(automodpackDirName()).normalize();
		this.generationsDirectory = this.gameDirectory.resolve(clientGenerationsDir).normalize();
		this.objectsDirectory = this.gameDirectory.resolve(clientGenerationObjectsDir).normalize();
		this.recordsDirectory = this.gameDirectory.resolve(clientGenerationRecordsDir).normalize();
		this.overlaysDirectory = this.gameDirectory.resolve(clientGenerationOverlaysDir).normalize();
		this.baselinesDirectory = this.gameDirectory.resolve(clientGenerationBaselinesDir).normalize();
		this.activeDirectory = this.gameDirectory.resolve(clientGenerationActiveDir).normalize();
		this.incomingDirectory = this.gameDirectory.resolve(clientGenerationIncomingDir).normalize();
		this.backupDirectory = this.gameDirectory.resolve(clientGenerationBackupDir).normalize();
		this.stateFile = this.gameDirectory.resolve(clientGenerationStateFile).normalize();
		this.transactionFile = this.gameDirectory.resolve(Constants.transactionFile).normalize();
		this.selectionFile = this.gameDirectory.resolve(clientSelectionFile).normalize();
		this.dummyFilesFile = this.gameDirectory.resolve(clientDummyFilesFile).normalize();
		this.restartLoopStateFile = this.gameDirectory.resolve(clientRestartLoopStateFile).normalize();
		this.clientConfigFile = this.gameDirectory.resolve(Constants.clientConfigFile).normalize();
		this.clientSecretsFile = this.gameDirectory.resolve(Constants.clientSecretsFile).normalize();
		this.cacheDirectory = this.gameDirectory.resolve(cacheDir).normalize();
		this.hashCacheFile = this.gameDirectory.resolve(hashCacheDBFile).normalize();
		this.modCacheFile = this.gameDirectory.resolve(modCacheDBFile).normalize();
		this.modpackContentTempFile = this.gameDirectory.resolve(Constants.modpackContentTempFile).normalize();
		this.helperDirectory = this.gameDirectory.resolve(Constants.helperDir).normalize();
		this.knownHostsFile = this.gameDirectory.resolve(Constants.knownHostsFile).normalize();
		this.knownHostsBootstrapFile = this.gameDirectory.resolve(Constants.knownHostsBootstrapFile).normalize();
		this.recoveryDirectory = this.gameDirectory.resolve(recoveryDir).normalize();
		this.privateDirectory = this.gameDirectory.resolve(privateDir).normalize();
		validateLayout();
	}

	public static ClientStorage fromGameDirectory(Path gameDirectory) {
		return new ClientStorage(gameDirectory);
	}

	public Path gameDirectory() {
		return gameDirectory;
	}

	public Path automodpackDirectory() {
		return automodpackDirectory;
	}

	public Path generationsDirectory() {
		return generationsDirectory;
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

	public Path dummyFilesFile() {
		return dummyFilesFile;
	}

	public Path restartLoopStateFile() {
		return restartLoopStateFile;
	}

	public Path clientConfigFile() {
		return clientConfigFile;
	}

	public Path clientSecretsFile() {
		return clientSecretsFile;
	}

	public Path cacheDirectory() {
		return cacheDirectory;
	}

	public Path hashCacheFile() {
		return hashCacheFile;
	}

	public Path modCacheFile() {
		return modCacheFile;
	}

	public Path modpackContentTempFile() {
		return modpackContentTempFile;
	}

	public Path helperDirectory() {
		return helperDirectory;
	}

	public Path knownHostsFile() {
		return knownHostsFile;
	}

	public Path knownHostsBootstrapFile() {
		return knownHostsBootstrapFile;
	}

	public Path privateDirectory() {
		return privateDirectory;
	}

	public Path modsDirectory() {
		return gameDirectory.resolve("mods").normalize();
	}

	public Path generationDirectory(String generationId) {
		return recordsDirectory.resolve(requireDigest(generationId, "generation ID")).normalize();
	}

	public Path generationManifest(String generationId) {
		return generationDirectory(generationId).resolve("manifest.json");
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
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return "";
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client overlay root is not a directory: " + root);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			try (Stream<Path> paths = Files.walk(root)) {
				for (Path path : paths.filter(candidate -> !candidate.equals(root)).sorted().toList()) {
					if (Files.isSymbolicLink(path)) throw new IOException("Client overlay contains a symbolic link: " + path);
					if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
					String relative = LogicalPath.normalize(root.relativize(path).toString());
					String hash = HashUtils.getHash(path);
					if (hash == null) throw new IOException("Cannot hash client overlay file: " + path);
					digest.update((relative + "\0" + Files.size(path) + "\0" + hash.toLowerCase(java.util.Locale.ROOT) + "\n").getBytes(StandardCharsets.UTF_8));
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-1 is required by the client protocol", e);
		}
	}

	public void ensureRoots() throws IOException {
		ensureDirectory(generationsDirectory, "client generation root");
		ensureDirectory(objectsDirectory, "client object store");
		ensureDirectory(recordsDirectory, "client generation records");
		ensureDirectory(overlaysDirectory, "client overlays");
		ensureDirectory(baselinesDirectory, "client baselines");
		ensureDirectory(incomingDirectory, "client transaction incoming root");
		ensureDirectory(backupDirectory, "client transaction backup root");
		ensureDirectory(privateDirectory, "client private state");
		ensureDirectory(cacheDirectory, "client cache");
	}

	public Jsons.ClientGenerationStateFields readActiveState() throws IOException {
		if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return null;
		if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Client active state is not a regular file");
		Jsons.ClientGenerationStateFields state = ConfigTools.read(stateFile, Jsons.ClientGenerationStateFields.class)
				.orElseThrow(() -> new IOException("Client active state is empty"));
		if (state.schemaVersion != 1 || !ModpackId.isValid(state.modpackId) || !DIGEST.matcher(state.generationId).matches()
				|| state.platform == null || state.platform.isBlank() || !DIGEST.matcher(state.stateDigest).matches() || !DIGEST.matcher(state.ledgerDigest).matches())
			throw new IOException("Client active state identity is invalid");
		return state;
	}

	public void writeActiveState(String modpackId, String generationId, String platform, String stateDigest, String ledgerDigest) throws IOException {
		Jsons.ClientGenerationStateFields state = new Jsons.ClientGenerationStateFields();
		state.modpackId = ModpackId.requireValid(modpackId);
		state.generationId = requireDigest(generationId, "generation ID");
		state.platform = Objects.requireNonNull(platform, "platform");
		state.stateDigest = requireDigest(stateDigest, "state digest");
		state.ledgerDigest = requireDigest(ledgerDigest, "ledger digest");
		Files.createDirectories(stateFile.getParent());
		ConfigTools.writeAtomic(stateFile, state);
	}

	public void clearActiveState() throws IOException {
		Files.deleteIfExists(stateFile);
	}

	private void validateLayout() {
		validateWithin(gameDirectory, automodpackDirectory);
		validateWithin(automodpackDirectory, generationsDirectory, transactionFile, selectionFile, dummyFilesFile, clientConfigFile, clientSecretsFile,
				cacheDirectory, modpackContentTempFile, knownHostsBootstrapFile, recoveryDirectory, privateDirectory);
		validateWithin(generationsDirectory, objectsDirectory, recordsDirectory, overlaysDirectory, baselinesDirectory, activeDirectory, incomingDirectory, backupDirectory,
				stateFile);
		validateWithin(cacheDirectory, hashCacheFile, modCacheFile, helperDirectory);
		validateWithin(privateDirectory, restartLoopStateFile, knownHostsFile);
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
		return value.toLowerCase(java.util.Locale.ROOT);
	}

	private static String requireTransactionId(String value) {
		try {
			return java.util.UUID.fromString(value).toString();
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid transaction UUID", e);
		}
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
