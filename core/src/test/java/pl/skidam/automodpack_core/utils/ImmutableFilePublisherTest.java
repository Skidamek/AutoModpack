package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImmutableFilePublisherTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void refusesFilesystemsWithoutAnEnforceableReadOnlyPolicy() throws Exception {
		URI archive = URI.create("jar:" + temporaryDirectory.resolve("publication.zip").toUri());
		try (FileSystem fileSystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			Path target = fileSystem.getPath("/immutable.json");
			byte[] original = "original".getBytes(StandardCharsets.UTF_8);
			assertThrows(IOException.class, () -> ImmutableFilePublisher.publishBytes(target, original, this::requireOriginal));
			assertFalse(Files.exists(target));
		}
	}

	@Test
	void protectsAnExistingValidTarget() throws Exception {
		Path target = Files.writeString(temporaryDirectory.resolve("immutable.json"), "original", StandardCharsets.UTF_8);

		assertFalse(ImmutableFilePublisher.publishBytes(target, "original".getBytes(StandardCharsets.UTF_8), this::requireOriginal));
		assertTrue(ImmutableFiles.isProtected(target));
	}

	private void requireOriginal(Path path) throws IOException {
		assertArrayEquals("original".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path));
	}
}
