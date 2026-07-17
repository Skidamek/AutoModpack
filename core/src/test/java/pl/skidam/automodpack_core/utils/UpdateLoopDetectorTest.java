package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateLoopDetectorTest {

	@TempDir
	Path tempDir;

	@Test
	void suppressesThirdRapidRestartAcrossInstances() {
		Path stateFile = tempDir.resolve("restart-state.json");
		AtomicLong now = new AtomicLong(1_000);

		assertEquals(UpdateLoopDetector.Decision.RESTART, detector(stateFile, now).evaluateAndRecord("same-state"));
		now.addAndGet(30_000);
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector(stateFile, now).evaluateAndRecord("same-state"));
		now.addAndGet(30_000);
		assertEquals(UpdateLoopDetector.Decision.SUPPRESS, detector(stateFile, now).evaluateAndRecord("same-state"));
	}

	@Test
	void changedOrExpiredStateStartsNewSequence() {
		Path stateFile = tempDir.resolve("restart-state.json");
		AtomicLong now = new AtomicLong(1_000);
		UpdateLoopDetector detector = detector(stateFile, now);

		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("first-state"));
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("changed-state"));
		now.addAndGet(60_001);
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("changed-state"));
	}

	@Test
	void malformedStateAndClearFailOpen() throws IOException {
		Path stateFile = tempDir.resolve("restart-state.json");
		AtomicLong now = new AtomicLong(1_000);
		Files.writeString(stateFile, "not json");

		UpdateLoopDetector detector = detector(stateFile, now);
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("same-state"));
		now.addAndGet(1_000);
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("same-state"));
		detector.clear();
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector(stateFile, now).evaluateAndRecord("same-state"));
	}

	private UpdateLoopDetector detector(Path stateFile, AtomicLong now) {
		return new UpdateLoopDetector(stateFile, now::get);
	}
}
