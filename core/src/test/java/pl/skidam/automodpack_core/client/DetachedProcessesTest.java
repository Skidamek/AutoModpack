package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
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
		Path childPid = temp.resolve("child.pid");
		Path parentExited = temp.resolve("parent-exited");
		Path stopChild = temp.resolve("stop-child");
		Path childLog = temp.resolve("child.log");
		Path parentLog = temp.resolve("parent.log");
		String classpath = System.getProperty("java.class.path");
		Path java = DetachedProcesses.javaExecutable();
		Process parent = null;
		ProcessHandle child = null;
		try {
			parent = new ProcessBuilder(java.toString(), "-Xmx64M", "-cp", classpath, ParentExits.class.getName(), marker.toAbsolutePath().toString(), childPid.toAbsolutePath().toString(),
					parentExited.toAbsolutePath().toString(), stopChild.toAbsolutePath().toString(), childLog.toAbsolutePath().toString(), temp.toAbsolutePath().toString(), classpath)
					.directory(temp.toFile()).redirectErrorStream(true).redirectOutput(parentLog.toFile()).start();
			assertTrue(parent.waitFor(30, TimeUnit.SECONDS), "parent spawn timed out");
			assertEquals(0, parent.exitValue(), () -> readIfPresent(parentLog) + readIfPresent(childLog));
			String pidText = awaitNonEmpty(childPid, 10);
			assertTrue(!pidText.isEmpty(), "detached child did not start; parent=" + readIfPresent(parentLog) + " child=" + readIfPresent(childLog));
			child = ProcessHandle.of(Long.parseLong(pidText)).orElseThrow();
			assertTrue(child.isAlive(), "detached child exited before the parent");
			Files.writeString(parentExited, "exit", StandardCharsets.UTF_8);
			String alive = awaitNonEmpty(marker, 10);
			assertEquals("alive", alive.isEmpty() ? "missing; parent=" + readIfPresent(parentLog) + " child=" + readIfPresent(childLog) : alive);
			Files.writeString(stopChild, "stop", StandardCharsets.UTF_8);
			child.onExit().get(10, TimeUnit.SECONDS);
		} finally {
			if (parent != null && parent.isAlive()) {
				parent.destroyForcibly();
				parent.waitFor(10, TimeUnit.SECONDS);
			}
			Files.writeString(parentExited, "exit", StandardCharsets.UTF_8);
			Files.writeString(stopChild, "stop", StandardCharsets.UTF_8);
			if (child != null && child.isAlive()) child.onExit().get(10, TimeUnit.SECONDS);
		}
	}

	/** Windows can expose a 0-byte file after create and before the write lands; wait for bytes, not existence. */
	private static String awaitNonEmpty(Path file, long seconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (System.nanoTime() < deadline) {
			try {
				if (Files.isRegularFile(file)) {
					String text = Files.readString(file, StandardCharsets.UTF_8).trim();
					if (!text.isEmpty()) return text;
				}
			} catch (IOException ignored) {
			}
			Thread.sleep(50);
		}
		return "";
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
			Path childPid = Path.of(args[1]);
			Path parentExited = Path.of(args[2]);
			Path stopChild = Path.of(args[3]);
			Path log = Path.of(args[4]);
			Path dir = Path.of(args[5]);
			DetachedProcesses.start(ChildLives.class, List.of("-Xmx64M"), List.of(marker.toString(), childPid.toString(), parentExited.toString(), stopChild.toString()), dir, log, classpath(args[6]));
		}
	}

	public static final class ChildLives {
		public static void main(String[] args) throws Exception {
			DetachedProcesses.ignoreHangup();
			Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
			while (!Files.exists(Path.of(args[2]))) Thread.sleep(50);
			Files.writeString(Path.of(args[0]), "alive", StandardCharsets.UTF_8);
			while (!Files.exists(Path.of(args[3]))) Thread.sleep(50);
		}
	}
}
