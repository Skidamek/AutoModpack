package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeJarTest {

	@Test
	void runtimeJarSurvivesDeletingTheSourceJar(@TempDir Path temp) throws Exception {
		Path source = temp.resolve("mods").resolve("automodpack.jar");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "loaded jar");
		Path runtime = RuntimeJar.ensure(source, temp.resolve("helper"));
		Files.delete(source);
		assertTrue(Files.isRegularFile(runtime));
		assertEquals("loaded jar", Files.readString(runtime));
	}

	@Test
	void cleanupKeepsTheCurrentAndLeasedJars(@TempDir Path temp) throws Exception {
		Path dir = temp.resolve("helper");
		Path sourceA = temp.resolve("a.jar");
		Path sourceB = temp.resolve("b.jar");
		Path sourceC = temp.resolve("c.jar");
		Files.writeString(sourceA, "a");
		Files.writeString(sourceB, "b");
		Files.writeString(sourceC, "c");
		Path jarA = RuntimeJar.ensure(sourceA, dir);
		Path jarB = RuntimeJar.ensure(sourceB, dir);

		RuntimeJar.cleanup(dir, jarB);
		assertFalse(Files.exists(jarA));
		assertTrue(Files.exists(jarB));

		Path jarC = RuntimeJar.ensure(sourceC, dir);
		RuntimeJar.cleanup(dir, jarB);
		assertFalse(Files.exists(jarC));

		jarC = RuntimeJar.ensure(sourceC, dir);
		try (FileChannel channel = FileChannel.open(RuntimeJar.lockFile(jarC), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
				FileLock ignored = channel.lock(0, Long.MAX_VALUE, true)) {
			RuntimeJar.cleanup(dir, jarB);
			assertTrue(Files.exists(jarC));
		}

		RuntimeJar.cleanup(dir, jarB);
		assertTrue(Files.exists(jarB));
		assertFalse(Files.exists(jarC));
	}
}
