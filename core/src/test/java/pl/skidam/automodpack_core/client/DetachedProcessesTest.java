package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The helper and restart window must outlive the game process on every OS CI runs. */
class DetachedProcessesTest {

	@Test
	void nativeAccessFlagMatchesTheRuntime() {
		List<String> args = new ArrayList<>();
		DetachedProcesses.addNativeAccess(args);
		if (Runtime.version().feature() >= 22) assertEquals(List.of("--enable-native-access=ALL-UNNAMED"), args);
		else assertTrue(args.isEmpty());
	}

	@Test
	void grandchildSurvivesParentExit(@TempDir Path temp) throws Exception {
		Path marker = temp.resolve("alive.txt");
		Path childLog = temp.resolve("child.log");
		Path parentLog = temp.resolve("parent.log");
		String classpath = System.getProperty("java.class.path");
		Path java = DetachedProcesses.javaExecutable();
		Process parent = new ProcessBuilder(java.toString(), "-Xmx64M", "-cp", classpath, ParentExits.class.getName(), marker.toAbsolutePath().toString(), childLog.toAbsolutePath().toString(),
				temp.toAbsolutePath().toString(), classpath).directory(temp.toFile()).redirectErrorStream(true).redirectOutput(parentLog.toFile()).start();
		assertTrue(parent.waitFor(30, TimeUnit.SECONDS), "parent spawn timed out");
		assertEquals(0, parent.exitValue(), () -> readIfPresent(parentLog) + readIfPresent(childLog));
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline && !Files.isRegularFile(marker)) Thread.sleep(50);
		assertEquals("alive", Files.isRegularFile(marker) ? Files.readString(marker, StandardCharsets.UTF_8).trim() : "missing; parent=" + readIfPresent(parentLog) + " child=" + readIfPresent(childLog));
	}

	private static String readIfPresent(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
		} catch (Exception e) {
			return e.toString();
		}
	}

	private static List<Path> classpath(String value) {
		return Arrays.stream(value.split(File.pathSeparator)).map(Path::of).toList();
	}

	public static final class ParentExits {
		public static void main(String[] args) throws Exception {
			Path marker = Path.of(args[0]);
			Path log = Path.of(args[1]);
			Path dir = Path.of(args[2]);
			DetachedProcesses.start(ChildLives.class, List.of("-Xmx64M"), List.of(marker.toString()), dir, log, classpath(args[3]));
		}
	}

	public static final class ChildLives {
		public static void main(String[] args) throws Exception {
			DetachedProcesses.ignoreHangup();
			Thread.sleep(1500);
			Files.writeString(Path.of(args[0]), "alive", StandardCharsets.UTF_8);
		}
	}
}
