package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import com.google.gson.Gson;

import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.PlatformUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

public final class DetachedUpdateHelper {
	private static final String HELPER_MAIN = UpdateHelperMain.class.getName();

	private DetachedUpdateHelper() {}

	public static void launch() throws IOException {
		Path sourceJar = THIS_MOD_JAR.toAbsolutePath().normalize();
		if (!Files.isRegularFile(sourceJar)) throw new IOException("Runnable AutoModpack JAR is missing: " + sourceJar);
		ClientStorage storage = ClientStorage.open(GameDirectory.current());
		Path absoluteHelperDirectory = storage.helperDirectory();
		Files.createDirectories(absoluteHelperDirectory);

		long size = Files.size(sourceJar);
		String hash = HashUtils.getHash(sourceJar);
		if (hash == null) throw new IOException("Cannot hash the runnable AutoModpack JAR");
		Path helperJar = absoluteHelperDirectory.resolve("automodpack-update-helper-" + UUID.randomUUID() + ".jar");
		VerifiedFileTransfer.copyAtomic(sourceJar, helperJar, size, hash);

		Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS ? "java.exe" : "java").toAbsolutePath().normalize();
		if (!Files.isRegularFile(javaExecutable)) throw new IOException("Java executable is missing: " + javaExecutable);
		String classpath = String.join(File.pathSeparator, helperJar.toString(), runtimeDependency(Gson.class).toString(), runtimeDependency(LogManager.class).toString(),
				runtimeDependency(LoggerContext.class).toString());
		new ProcessBuilder(javaExecutable.toString(), "-cp", classpath, HELPER_MAIN, Long.toString(ProcessHandle.current().pid()))
				.directory(GameDirectory.current().toFile()).inheritIO().start();
		LOGGER.info("Launched detached update helper for the latest pending transaction from {}", helperJar);
	}

	public static void cleanupOldHelperJars() {
		cleanupOldHelperJars(ClientStorage.open(GameDirectory.current()).helperDirectory());
	}

	private static void cleanupOldHelperJars(Path directory) {
		if (!Files.isDirectory(directory)) return;
		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.filter(path -> path.getFileName().toString().startsWith("automodpack-update-helper-")
					&& JarUtils.hasJarExtension(path)).toList()) {
				try {
					Files.deleteIfExists(file);
				} catch (IOException e) {
					LOGGER.debug("Helper JAR is still in use: {}", file);
				}
			}
		} catch (IOException e) {
			LOGGER.debug("Failed to clean old update-helper JARs", e);
		}
	}

	private static Path runtimeDependency(Class<?> type) throws IOException {
		Path path;
		try {
			path = JarUtils.getJarPath(type).toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			throw new IOException("Cannot locate helper runtime dependency " + type.getName(), e);
		}
		if (!Files.isRegularFile(path) && !Files.isDirectory(path)) throw new IOException("Helper runtime dependency is unavailable: " + path);
		return path;
	}

}
