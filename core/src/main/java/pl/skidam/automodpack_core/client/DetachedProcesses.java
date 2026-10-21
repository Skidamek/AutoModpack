package pl.skidam.automodpack_core.client;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import pl.skidam.automodpack_core.utils.PlatformUtils;

/**
 * Starts a long-lived JVM that must outlive this process: a short-lived spawner JVM launches it and exits, so the
 * work process is not a direct child of the game. Unix hangup is ignored inside that work process. The caller supplies
 * the full classpath, so this class only knows how to detach; it does not decide which jar or dependencies are used.
 */
public final class DetachedProcesses {
	// Receipt: an empty -Xmx64M JVM returns in 11ms here; loading the work jar is still well under a second. 15s is the hung-spawn tripwire.
	private static final long SPAWNER_WAIT_SECONDS = 15;
	private static final String SPAWNER_HEAP = "-Xmx64M";

	private DetachedProcesses() {}

	/** Prefers {@code javaw} on Windows so a console window does not flash. */
	public static Path javaExecutable() throws IOException {
		Path bin = Path.of(System.getProperty("java.home"), "bin");
		if (PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS) {
			Path javaw = bin.resolve("javaw.exe");
			if (Files.isRegularFile(javaw)) return javaw.toAbsolutePath().normalize();
			Path java = bin.resolve("java.exe");
			if (Files.isRegularFile(java)) return java.toAbsolutePath().normalize();
		} else {
			Path java = bin.resolve("java");
			if (Files.isRegularFile(java)) return java.toAbsolutePath().normalize();
		}
		throw new IOException("Java executable is missing: " + bin);
	}

	public static void addNativeAccess(List<String> args) {
		if (Runtime.version().feature() >= 22) args.add("--enable-native-access=ALL-UNNAMED");
	}

	/** Starts {@code main} on {@code classpath} so it survives this process exiting. */
	public static void start(Class<?> main, List<String> jvmArgs, List<String> mainArgs, Path directory, Path log, List<Path> classpath) throws IOException {
		Path java = javaExecutable();
		String classpathString = joinClasspath(classpath);
		List<String> command = new ArrayList<>();
		command.add(java.toString());
		addNativeAccess(command);
		command.addAll(jvmArgs);
		command.add("-cp");
		command.add(classpathString);
		command.add(main.getName());
		command.addAll(mainArgs);
		Path logFile = log.toAbsolutePath().normalize();
		Path logParent = logFile.getParent();
		if (logParent != null) Files.createDirectories(logParent);
		List<String> spawner = new ArrayList<>();
		spawner.add(java.toString());
		spawner.add(SPAWNER_HEAP);
		spawner.add("-cp");
		spawner.add(classpathString);
		spawner.add(DetachedProcesses.class.getName());
		spawner.add(logFile.toString());
		spawner.addAll(command);
		Process process = new ProcessBuilder(spawner).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(Redirect.appendTo(logFile.toFile())).start();
		try {
			if (!process.waitFor(SPAWNER_WAIT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("Detached spawner did not exit within " + SPAWNER_WAIT_SECONDS + "s");
			}
			int code = process.exitValue();
			if (code != 0) throw new IOException("Detached spawn failed with exit code " + code);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted while spawning a detached process", e);
		}
	}

	/** Short-lived launcher: starts the work command and exits so that process is not a direct child of the game. */
	public static void main(String[] args) throws IOException {
		if (args.length < 2) throw new IOException("Expected log path and command");
		Path log = Path.of(args[0]).toAbsolutePath().normalize();
		Path parent = log.getParent();
		if (parent != null) Files.createDirectories(parent);
		List<String> command = Arrays.asList(args).subList(1, args.length);
		Path nullIn = PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS ? Path.of("NUL") : Path.of("/dev/null");
		new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(Redirect.appendTo(log.toFile())).redirectInput(Redirect.from(nullIn.toFile())).start();
	}

	/** Best-effort ignore of SIGHUP so a Unix work process survives the game session ending. */
	public static void ignoreHangup() {
		if (PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS) return;
		try {
			Class<?> signal = Class.forName("sun.misc.Signal");
			Class<?> handler = Class.forName("sun.misc.SignalHandler");
			Object hup = signal.getConstructor(String.class).newInstance("HUP");
			Object ign = handler.getField("SIG_IGN").get(null);
			signal.getMethod("handle", signal, handler).invoke(null, hup, ign);
		} catch (Throwable ignored) {
		}
	}

	private static String joinClasspath(List<Path> classpath) throws IOException {
		if (classpath.isEmpty()) throw new IOException("Detached process classpath is empty");
		List<String> entries = new ArrayList<>();
		for (Path entry : classpath) {
			Path absolute = entry.toAbsolutePath().normalize();
			if (!Files.isRegularFile(absolute) && !Files.isDirectory(absolute)) throw new IOException("Detached process classpath entry is unavailable: " + absolute);
			entries.add(absolute.toString());
		}
		return String.join(File.pathSeparator, entries);
	}
}
