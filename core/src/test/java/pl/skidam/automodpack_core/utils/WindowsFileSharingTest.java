package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsFileSharingTest {
	private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

	@TempDir
	Path tempDir;

	@Test
	void separateJvmHashesJarHeldOpenByJava() throws Exception {
		Path jar = createJar();
		try (Holder ignored = startHolder("hold-jar", jar)) {
			assertChildSucceeded(runChild("hash", jar));
			assertChildSucceeded(runChild("read-random-access", jar));
		}
	}

	@Test
	void separateJvmHashesFileHeldByRandomAccessReader() throws Exception {
		Path file = Files.writeString(tempDir.resolve("random-access-held.txt"), "content");
		try (Holder ignored = startHolder("hold-random-access", file)) {
			assertChildSucceeded(runChild("hash", file));
		}
	}

	@Test
	void separateJvmCanDeleteFileHeldByNioReader() throws Exception {
		Path file = Files.writeString(tempDir.resolve("nio-reader.txt"), "content");
		try (Holder ignored = startHolder("hold-nio", file)) {
			assertChildSucceeded(runChild("delete", file));
			assertFalse(Files.exists(file));
		}
	}

	@Test
	void legacyRandomAccessReaderPreventsSeparateJvmDelete() throws Exception {
		Path file = Files.writeString(tempDir.resolve("random-access-reader.txt"), "content");
		try (Holder ignored = startHolder("hold-random-access", file)) {
			ChildResult result = runChild("delete", file);
			assertNotEquals(0, result.exitCode(), result.output());
			assertTrue(Files.exists(file));
		}
	}

	@Test
	void neitherReaderBypassesAHandleThatDeniesSharedReads() throws Exception {
		Path file = Files.writeString(tempDir.resolve("exclusive-reader.txt"), "content");
		try (Holder ignored = startHolder("hold-without-share-read", file)) {
			ChildResult nioResult = runChild("hash", file);
			assertNotEquals(0, nioResult.exitCode(), nioResult.output());

			ChildResult randomAccessResult = runChild("read-random-access", file);
			assertNotEquals(0, randomAccessResult.exitCode(), randomAccessResult.output());
		}
	}

	public static void main(String[] args) throws Exception {
		String mode = args[0];
		Path file = Path.of(args[1]);
		switch (mode) {
			case "hold-jar" -> hold(new JarFile(file.toFile()), Path.of(args[2]));
			case "hold-nio" -> hold(FileStreams.openChannel(file), Path.of(args[2]));
			case "hold-random-access" -> hold(new RandomAccessFile(file.toFile(), "r"), Path.of(args[2]));
			case "hold-without-share-read" -> hold(openWithoutShareRead(file), Path.of(args[2]));
			case "hash" -> {
				if (HashUtils.getHash(file) == null) throw new IOException("NIO-backed hash could not read " + file);
			}
			case "read-random-access" -> {
				try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
					while (input.read() >= 0) {
					}
				}
			}
			case "delete" -> Files.delete(file);
			default -> throw new IllegalArgumentException("Unknown child mode: " + mode);
		}
	}

	private Path createJar() throws IOException {
		Path jar = tempDir.resolve("loaded.jar");
		try (OutputStream output = Files.newOutputStream(jar); JarOutputStream archive = new JarOutputStream(output)) {
			archive.putNextEntry(new JarEntry("example.txt"));
			archive.write("content".getBytes(StandardCharsets.UTF_8));
			archive.closeEntry();
		}
		return jar;
	}

	private Holder startHolder(String mode, Path file) throws Exception {
		Path ready = tempDir.resolve(mode + "-" + UUID.randomUUID() + ".ready");
		Process process = childProcess(mode, file, ready).start();
		long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
		while (!Files.exists(ready)) {
			if (!process.isAlive()) fail("Holder process exited early:\n" + readOutput(process));
			if (System.nanoTime() >= deadline) {
				process.destroyForcibly();
				fail("Timed out waiting for holder process");
			}
			Thread.sleep(20);
		}
		return new Holder(process);
	}

	private ChildResult runChild(String mode, Path file) throws Exception {
		Process process = childProcess(mode, file).start();
		if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
			process.destroyForcibly();
			fail("Timed out waiting for child mode " + mode);
		}
		return new ChildResult(process.exitValue(), readOutput(process));
	}

	private ProcessBuilder childProcess(String mode, Path file, Path... extraArguments) {
		List<String> command = new ArrayList<>();
		command.add(javaExecutable().toString());
		command.add("-cp");
		command.add(testClasspath());
		command.add(WindowsFileSharingTest.class.getName());
		command.add(mode);
		command.add(file.toString());
		for (Path argument : extraArguments) command.add(argument.toString());
		return new ProcessBuilder(command).redirectErrorStream(true);
	}

	private static Path javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", "java.exe");
	}

	private static String testClasspath() {
		Set<String> entries = new LinkedHashSet<>();
		entries.addAll(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
		for (ClassLoader loader = WindowsFileSharingTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
			if (loader instanceof URLClassLoader urlClassLoader) {
				for (URL url : urlClassLoader.getURLs()) {
					if ("file".equals(url.getProtocol())) {
						try {
							entries.add(Path.of(url.toURI()).toString());
						} catch (Exception e) {
							throw new IllegalStateException("Invalid test classpath URL: " + url, e);
						}
					}
				}
			}
		}
		return String.join(java.io.File.pathSeparator, entries);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static FileChannel openWithoutShareRead(Path file) throws Exception {
		Class<? extends Enum> optionType = (Class<? extends Enum>) Class.forName("com.sun.nio.file.ExtendedOpenOption").asSubclass(Enum.class);
		OpenOption noShareRead = (OpenOption) Enum.valueOf(optionType, "NOSHARE_READ");
		return FileChannel.open(file, Set.of(StandardOpenOption.READ, noShareRead));
	}

	private static void hold(AutoCloseable handle, Path ready) throws Exception {
		try (handle) {
			Files.writeString(ready, "ready");
			System.in.read();
		}
	}

	private static String readOutput(Process process) throws IOException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			return reader.lines().reduce("", (left, right) -> left + right + System.lineSeparator());
		}
	}

	private static void assertChildSucceeded(ChildResult result) {
		assertEquals(0, result.exitCode(), result.output());
	}

	private record ChildResult(int exitCode, String output) {}

	private record Holder(Process process) implements AutoCloseable {
		@Override
		public void close() throws Exception {
			process.getOutputStream().close();
			if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				fail("Timed out stopping holder process");
			}
			assertEquals(0, process.exitValue(), readOutput(process));
		}
	}
}
