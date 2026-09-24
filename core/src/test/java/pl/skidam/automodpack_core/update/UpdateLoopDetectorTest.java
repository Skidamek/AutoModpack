package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateLoopDetectorTest {

	@TempDir
	Path tempDir;

	@Test
	void suppressesThirdRapidRestartAcrossInstances() throws IOException {
		Path stateFile = tempDir.resolve("restart-state.json");
		AtomicLong now = new AtomicLong(1_000);

		assertEquals(UpdateLoopDetector.Decision.RESTART, detector(stateFile, now).evaluateAndRecord("same-state").decision());
		now.addAndGet(30_000);
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector(stateFile, now).evaluateAndRecord("same-state").decision());
		now.addAndGet(30_000);
		assertEquals(UpdateLoopDetector.Decision.SUPPRESS, detector(stateFile, now).evaluateAndRecord("same-state").decision());
	}

	@Test
	void countsRestartsUpToTheCap() throws IOException {
		Path stateFile = tempDir.resolve("restart-state.json");
		AtomicLong now = new AtomicLong(1_000);
		UpdateLoopDetector detector = detector(stateFile, now);

		UpdateLoopDetector.Outcome first = detector.evaluateAndRecord("same-state");
		assertEquals(UpdateLoopDetector.Decision.RESTART, first.decision());
		assertEquals(1, first.restarts());
		assertEquals(2, first.maxRestarts());
		UpdateLoopDetector.Outcome second = detector.evaluateAndRecord("same-state");
		assertEquals(UpdateLoopDetector.Decision.RESTART, second.decision());
		assertEquals(2, second.restarts());
		UpdateLoopDetector.Outcome third = detector.evaluateAndRecord("same-state");
		assertEquals(UpdateLoopDetector.Decision.SUPPRESS, third.decision());
		assertEquals(2, third.restarts());
	}

	@Test
	void changedOrExpiredStateStartsNewSequence() throws IOException {
		Path stateFile = tempDir.resolve("restart-state.json");
		AtomicLong now = new AtomicLong(1_000);
		UpdateLoopDetector detector = detector(stateFile, now);

		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("first-state").decision());
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("changed-state").decision());
		now.addAndGet(60_001);
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("changed-state").decision());
	}

	@Test
	void malformedStateAndClearFailOpen() throws IOException {
		Path stateFile = tempDir.resolve("restart-state.json");
		AtomicLong now = new AtomicLong(1_000);
		Files.writeString(stateFile, "not json");

		UpdateLoopDetector detector = detector(stateFile, now);
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("same-state").decision());
		now.addAndGet(1_000);
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("same-state").decision());
		detector.clear();
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector(stateFile, now).evaluateAndRecord("same-state").decision());
	}

	@Test
	void customPolicyWithoutWindowDoesNotExpire() throws IOException {
		Path stateFile = tempDir.resolve("stuck-transaction-state.json");
		AtomicLong now = new AtomicLong(1_000);

		UpdateLoopDetector detector = new UpdateLoopDetector(stateFile, now::get, 3, null);
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("stuck-transaction").decision());
		now.addAndGet(Duration.ofDays(30).toMillis());
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("stuck-transaction").decision());
		now.addAndGet(Duration.ofDays(30).toMillis());
		assertEquals(UpdateLoopDetector.Decision.RESTART, detector.evaluateAndRecord("stuck-transaction").decision());
		now.addAndGet(Duration.ofDays(30).toMillis());
		assertEquals(UpdateLoopDetector.Decision.SUPPRESS, detector.evaluateAndRecord("stuck-transaction").decision());
		assertEquals(UpdateLoopDetector.Decision.SUPPRESS, new UpdateLoopDetector(stateFile, now::get, 3, null).evaluateAndRecord("stuck-transaction").decision());
	}

	private UpdateLoopDetector detector(Path stateFile, AtomicLong now) {
		return new UpdateLoopDetector(stateFile, now::get);
	}
}
