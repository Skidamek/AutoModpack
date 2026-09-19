package pl.skidam.automodpack_core.protocol.netty;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The ring bound, the exact counters and the span lifecycle are the tracker's whole contract. */
class ActivityTrackerTest {
	private ActivityTracker.Span span(ActivityTracker tracker, String actor, String routeKey) {
		ActivityTracker.Span span = tracker.start("10.0.0.1:40000");
		span.actor = actor;
		span.routeKey = routeKey;
		return span;
	}

	@Test
	void ringStaysBoundedWhileCountersStayExact() {
		ActivityTracker tracker = new ActivityTracker();
		for (int i = 0; i < 300; i++) tracker.complete(span(tracker, "Steve", "head"), 200, 10);
		ActivityTracker.Snapshot snapshot = tracker.snapshot(Map.of());
		assertEquals(300, snapshot.totalRequests());
		assertEquals(3000, snapshot.totalBytes());
		assertEquals(256, snapshot.recent().size());
		assertEquals(1, snapshot.players().size());
		assertEquals(300, snapshot.players().get(0).requests());
	}

	@Test
	void unchangedChecksCountButNeverRing() {
		ActivityTracker tracker = new ActivityTracker();
		tracker.complete(span(tracker, "Steve", "head"), 304, 0);
		ActivityTracker.Snapshot snapshot = tracker.snapshot(Map.of());
		assertEquals(1, snapshot.totalRequests());
		assertEquals(1, snapshot.unchangedChecks());
		assertTrue(snapshot.recent().isEmpty());
		assertTrue(snapshot.inFlight().isEmpty());
	}

	@Test
	void inFlightSpansShowProgressAndThenComplete() {
		ActivityTracker tracker = new ActivityTracker();
		ActivityTracker.Span span = span(tracker, "Alex", "a".repeat(40));
		tracker.progress(span, 1024);
		ActivityTracker.Snapshot during = tracker.snapshot(Map.of());
		assertEquals(1, during.inFlight().size());
		assertEquals(1024, during.inFlight().get(0).bytes());
		tracker.complete(span, 206, 4096);
		ActivityTracker.Snapshot after = tracker.snapshot(Map.of());
		assertTrue(after.inFlight().isEmpty());
		assertEquals(1, after.recent().size());
		assertEquals(4096, after.recent().get(0).bytes());
		assertEquals("Alex", after.recent().get(0).actor());
	}

	@Test
	void droppedSpansCompleteOnceAndResolveNamesAgainstTheGeneration() {
		ActivityTracker tracker = new ActivityTracker();
		String sha1 = "a".repeat(40);
		ActivityTracker.Span dropped = span(tracker, "Steve", sha1);
		tracker.progress(dropped, 2048);
		tracker.completeDropped(dropped);
		tracker.complete(dropped, 500, 10); // a re-completion after the drop is ignored
		ActivityTracker.Snapshot snapshot = tracker.snapshot(Map.of(sha1, "mods/create.jar"));
		assertEquals(1, snapshot.totalRequests());
		assertEquals(1, snapshot.recent().size());
		assertEquals(0, snapshot.recent().get(0).status());
		assertEquals(2048, snapshot.recent().get(0).bytes());
		assertEquals("mods/create.jar", snapshot.recent().get(0).displayName());
		assertEquals(1, snapshot.players().get(0).requests());
	}
}
