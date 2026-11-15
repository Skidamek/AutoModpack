package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ImmutableForceFailureTest {
	@Test
	void everyIoErrorFromTheForceInvocationIsTerminal() throws Exception {
		for (var cause : List.of(new IOException("Input/output error"), new AccessDeniedException("sharing"), new ClosedByInterruptException())) {
			AtomicInteger calls = new AtomicInteger();
			try (var channel = new ForceChannel(cause, calls)) {
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
		try (var channel = new ForceChannel(null, calls)) {
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
		try (var channel = new ForceChannel(null, calls)) {
			ImmutableFiles.force(channel, Path.of("held.obj"));
			assertEquals(1, calls.get());
			assertTrue(channel.metadata);
		}
	}

	/** A force call that reports the given error, so no real media fault or native code is needed. */
	private static final class ForceChannel extends FileChannel {
		private final IOException error;
		private final AtomicInteger calls;
		private boolean metadata;
		ForceChannel(IOException error, AtomicInteger calls) {
			this.error = error;
			this.calls = calls;
		}

		@Override
		public void force(boolean metadata) throws IOException {
			this.metadata = metadata;
			calls.incrementAndGet();
			if (error instanceof ClosedByInterruptException) Thread.currentThread().interrupt();
			if (error != null) throw error;
		}

		@Override
		protected void implCloseChannel() {}

		@Override
		public int read(ByteBuffer buffer) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long read(ByteBuffer[] buffers, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int write(ByteBuffer buffer) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long write(ByteBuffer[] buffers, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long position() {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileChannel position(long position) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long size() {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileChannel truncate(long size) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long transferTo(long position, long count, WritableByteChannel target) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long transferFrom(ReadableByteChannel source, long position, long count) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int read(ByteBuffer buffer, long position) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int write(ByteBuffer buffer, long position) {
			throw new UnsupportedOperationException();
		}

		@Override
		public MappedByteBuffer map(MapMode mode, long position, long size) {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileLock lock(long position, long size, boolean shared) {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileLock tryLock(long position, long size, boolean shared) {
			throw new UnsupportedOperationException();
		}
	}
}
