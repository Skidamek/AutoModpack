package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** A force-call failure, not an open/protection failure; no real media fault or native code required. */
public final class ForceFailureFixtures {
	private ForceFailureFixtures() {}

	public static IOException reportedAtForce(Path path, IOException cause) {
		try (var channel = new Channel(cause, new AtomicInteger())) {
			ImmutableFiles.force(channel, path);
			throw new AssertionError("missing force error");
		} catch (ImmutableFiles.ForceFailedException failure) {
			return failure;
		} catch (IOException failure) {
			throw new AssertionError("force error not classified", failure);
		}
	}
	static final class Channel extends FileChannel {
		private final IOException error;
		final AtomicInteger calls;
		boolean metadata;
		Channel(IOException error, AtomicInteger calls) {
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
		public int read(ByteBuffer b) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long read(ByteBuffer[] b, int o, int l) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int write(ByteBuffer b) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long write(ByteBuffer[] b, int o, int l) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long position() {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileChannel position(long p) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long size() {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileChannel truncate(long s) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long transferTo(long p, long c, WritableByteChannel t) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long transferFrom(ReadableByteChannel s, long p, long c) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int read(ByteBuffer b, long p) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int write(ByteBuffer b, long p) {
			throw new UnsupportedOperationException();
		}

		@Override
		public MappedByteBuffer map(MapMode m, long p, long s) {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileLock lock(long p, long s, boolean shared) {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileLock tryLock(long p, long s, boolean shared) {
			throw new UnsupportedOperationException();
		}
	}
}
