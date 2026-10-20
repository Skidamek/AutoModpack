package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImmutableFilesTest {
	@Test
	void secondProtectOnTheSameStoreLeavesNoProbeFiles(@TempDir Path directory) throws IOException {
		Path first = Files.writeString(directory.resolve("a.bin"), "a", StandardCharsets.UTF_8);
		Path second = Files.writeString(directory.resolve("b.bin"), "b", StandardCharsets.UTF_8);
		try {
			ImmutableFiles.protect(first);
			ImmutableFiles.protect(second);
			try (Stream<Path> stream = Files.list(directory)) {
				assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith(".read-only-probe-")));
			}
		} finally {
			ImmutableFiles.unprotect(first);
			ImmutableFiles.unprotect(second);
		}
	}
}
