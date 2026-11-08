package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
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
			assertEquals(1, forceEvents(recording, file), "a content force is never skipped or followed by a redundant protection force");
		} finally {
			ImmutableFiles.unprotect(file);
			ImmutableFiles.unprotect(control);
		}
	}

	@Test
	void protectAndForceReopensAFileSealedByAnEarlierRun(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("sealed.bin"), "verified content");
		Path control = Files.writeString(directory.resolve("control.bin"), "control");
		ImmutableFiles.protect(control);
		boolean expectedProtection = ImmutableFiles.isProtected(control);
		try {
			ImmutableFiles.protectAndForce(file);
			assertEquals(expectedProtection, ImmutableFiles.isProtected(file));
			ImmutableFiles.protectAndForce(file);
			assertEquals("verified content", Files.readString(file));
			assertEquals(expectedProtection, ImmutableFiles.isProtected(file), "resealing leaves the same policy");
		} finally {
			ImmutableFiles.unprotect(file);
			ImmutableFiles.unprotect(control);
		}
	}

	@Test
	void reopeningASealedFileStillForcesItOnce(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("sealed.bin"), "verified content");
		ImmutableFiles.protectAndForce(file);
		Path recording = directory.resolve("reopen-forces.jfr");
		try (Recording events = new Recording()) {
			events.enable("jdk.FileForce").withThreshold(Duration.ZERO);
			events.start();
			ImmutableFiles.protectAndForce(file);
			events.stop();
			events.dump(recording);
		}
		try {
			assertEquals("verified content", Files.readString(file));
			assertEquals(1, forceEvents(recording, file), "reopening a sealed file still forces its content once");
		} finally {
			ImmutableFiles.unprotect(file);
		}
	}

	private static long forceEvents(Path recording, Path file) throws IOException {
		Path expected = file.toAbsolutePath().normalize();
		return RecordingFile.readAllEvents(recording).stream()
				.filter(event -> event.getEventType().getName().equals("jdk.FileForce"))
				.map(event -> event.getString("path"))
				.filter(Objects::nonNull)
				.map(path -> Path.of(path).toAbsolutePath().normalize())
				.filter(expected::equals)
				.count();
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
