package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ImmutableForceFailureTest {
	@Test
	void everyIoErrorFromTheForceInvocationIsTerminal() throws Exception {
		for (var cause : List.of(new IOException("Input/output error"), new AccessDeniedException("sharing"), new ClosedByInterruptException())) {
			AtomicInteger calls = new AtomicInteger();
			try (var channel = new ForceFailureFixtures.Channel(cause, calls)) {
				var failure = assertThrows(ImmutableFiles.ForceFailedException.class, () -> ImmutableFiles.force(channel, Path.of("held.obj")));
				assertSame(cause, failure.getCause());
				assertEquals(1, calls.get());
				assertTrue(channel.metadata);
			} finally {
				Thread.interrupted();
			}
		}
	}

	@Test
	void aKnownPreCallInterruptNeverInvokesTheForceAndStaysRetryable() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (var channel = new ForceFailureFixtures.Channel(null, calls)) {
			Thread.currentThread().interrupt();
			assertThrows(ClosedByInterruptException.class, () -> ImmutableFiles.force(channel, Path.of("held.obj")));
			assertEquals(0, calls.get());
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void successfulForcesKeepTheSameSingleMetadataBarrier() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (var channel = new ForceFailureFixtures.Channel(null, calls)) {
			ImmutableFiles.force(channel, Path.of("held.obj"));
			assertEquals(1, calls.get());
			assertTrue(channel.metadata);
		}
	}
}
