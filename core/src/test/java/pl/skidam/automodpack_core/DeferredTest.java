package pl.skidam.automodpack_core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class DeferredTest {

	@Test
	void queuedWorkRunsAtTheDrainAndNeverBefore() {
		Deferred deferred = new Deferred();
		List<String> ran = new ArrayList<>();
		deferred.run(() -> ran.add("first"));
		deferred.run(() -> ran.add("second"));
		assertEquals(List.of(), ran);
		deferred.drain();
		assertEquals(List.of("first", "second"), ran);
	}

	@Test
	void drainIsOnceAndWorkQueuedAfterItRunsInline() {
		Deferred deferred = new Deferred();
		List<String> ran = new ArrayList<>();
		deferred.run(() -> ran.add("queued"));
		deferred.drain();
		deferred.drain();
		deferred.run(() -> ran.add("after"));
		assertEquals(List.of("queued", "after"), ran);
	}

	@Test
	void anExceptionPropagatesInsteadOfKillingTheRestSilently() {
		Deferred deferred = new Deferred();
		deferred.run(() -> {
			throw new IllegalStateException("boom");
		});
		try {
			deferred.drain();
		} catch (IllegalStateException expected) {
			return;
		}
		throw new AssertionError("the drain must not swallow a task's failure");
	}
}
