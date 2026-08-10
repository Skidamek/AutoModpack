package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsFileSharingTest {
	@TempDir
	Path tempDir;

	@Test
	void nioReadChannelDoesNotPreventDeletion() throws Exception {
		Path file = Files.writeString(tempDir.resolve("shared-read.txt"), "content");
		try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.READ)) {
			Files.delete(file);
			assertFalse(Files.exists(file));
		}
	}

	@Test
	void hashesJarWhileJavaHasItOpen() throws Exception {
		Path jar = tempDir.resolve("loaded.jar");
		try (OutputStream output = Files.newOutputStream(jar); JarOutputStream archive = new JarOutputStream(output)) {
			archive.putNextEntry(new JarEntry("example.txt"));
			archive.write("content".getBytes(StandardCharsets.UTF_8));
			archive.closeEntry();
		}

		try (JarFile ignored = new JarFile(jar.toFile())) {
			assertNotNull(HashUtils.getHash(jar));
		}
	}
}
