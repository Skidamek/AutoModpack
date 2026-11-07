package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

class ImmutableFilesTest {
	@Test
	void protectionAndContentShareOneForce(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("private.bin"), "verified content");
		Path control = Files.writeString(directory.resolve("control.bin"), "control");
		ImmutableFiles.protect(control);
		boolean expectedProtection = ImmutableFiles.isProtected(control);
		Path recording = directory.resolve("forces.jfr");
		try (Recording events = new Recording()) {
			events.enable("jdk.FileForce").withThreshold(Duration.ZERO);
			events.start();
			ImmutableFiles.protectAndForce(file);
			events.stop();
			events.dump(recording);
		}
		try {
			assertEquals("verified content", Files.readString(file));
			assertEquals(expectedProtection, ImmutableFiles.isProtected(file), "the existing enforcement or degraded policy is unchanged");
			long forces = RecordingFile.readAllEvents(recording).stream()
					.filter(event -> event.getEventType().getName().equals("jdk.FileForce") && file.toString().equals(event.getString("path"))).count();
			assertEquals(1, forces, "a content force is never skipped or followed by a redundant protection force");
		} finally {
			ImmutableFiles.unprotect(file);
			ImmutableFiles.unprotect(control);
		}
	}

	@Test
	void forceAndProtectRejectsDirectoriesAndMissingFiles(@TempDir Path directory) {
		assertThrows(IOException.class, () -> ImmutableFiles.protectAndForce(directory));
		assertThrows(IOException.class, () -> ImmutableFiles.protectAndForce(directory.resolve("missing")));
	}

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
