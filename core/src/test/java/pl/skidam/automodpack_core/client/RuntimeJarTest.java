package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

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
	void cleanupKeepsTheCurrentJarAndDeletesTheRest(@TempDir Path temp) throws Exception {
		Path dir = temp.resolve("helper");
		Files.createDirectories(dir);
		Path sourceA = temp.resolve("a.jar");
		Path sourceB = temp.resolve("b.jar");
		Files.writeString(sourceA, "a");
		Files.writeString(sourceB, "b");
		Path jarA = RuntimeJar.ensure(sourceA, dir);
		Path jarB = RuntimeJar.ensure(sourceB, dir);
		Path leftoverRuntime = dir.resolve("automodpack-runtime-old.jar");
		Path leftoverLock = dir.resolve("automodpack-runtime-old.jar.lock");
		Path leftoverUuid = dir.resolve("automodpack-update-helper-deadbeef.jar");
		Files.writeString(leftoverRuntime, "old");
		Files.writeString(leftoverLock, "lock");
		Files.writeString(leftoverUuid, "uuid");

		RuntimeJar.cleanup(dir, jarB);
		assertFalse(Files.exists(jarA));
		assertTrue(Files.exists(jarB));
		assertFalse(Files.exists(leftoverRuntime));
		assertFalse(Files.exists(leftoverLock));
		assertFalse(Files.exists(leftoverUuid));
	}
}
