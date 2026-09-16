package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreesTest {
	@TempDir
	Path tempDir;

	@Test
	void deletesReadOnlyFileFromManagedTree() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("managed"));
		Path file = Files.writeString(root.resolve("object"), "immutable");
		DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
		Assumptions.assumeTrue(dos != null, "requires a DOS read-only attribute view");
		dos.setReadOnly(true);

		try {
			FileTrees.delete(root);
		} finally {
			if (Files.exists(file)) {
				dos.setReadOnly(false);
				Files.deleteIfExists(file);
			}
			Files.deleteIfExists(root);
		}

		assertFalse(Files.exists(root));
	}
}
