package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.airlift.compress.zstd.ZstdIncrementalFrameDecompressor;
import io.airlift.compress.zstd.ZstdInputStream;

/**
 * Lends zstd stream decoders so a client opening one {@link ZstdInputStream} per file reuses the ~400 KB of window,
 * entropy-table and input buffers instead of allocating them for every stream. A decoder serves exactly one open stream:
 * it is taken when the stream opens and comes back only once that stream is closed and no read is still running on it.
 * A stream that is never closed just keeps its decoder, which the GC then collects with it.
 *
 * <p>
 * A pooled decoder is built with {@link #MAX_WINDOW_BYTES} as its window, the window this protocol's own compressor
 * emits (single-segment frames up to 1 MiB, windowLog 20 above it, measured over 1 KiB..32 MiB inputs). The buffer is
 * sized from that declared window, not from the output, and never shrinks: measured with the fork, a decoder that saw a
 * stream below 1 MiB holds 256 KiB and one that saw a MiB-window stream holds 1792 KiB. So the pool charges each
 * decoder its {@link ZstdIncrementalFrameDecompressor#retainedWindowBytes() retained bytes} and keeps at most
 * {@link #MAX_IDLE} decoders within {@link #MAX_RETAINED_BYTES}, which is the whole memory bound of the idle pool.
 */
final class ZstdDecoderPool {
	static final int MAX_IDLE = 8;
	static final int MAX_WINDOW_BYTES = 1 << 20;
	static final long MAX_RETAINED_BYTES = 8L << 20;

	private static final ConcurrentLinkedQueue<ZstdIncrementalFrameDecompressor> IDLE = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger IDLE_COUNT = new AtomicInteger();
	private static final AtomicLong IDLE_BYTES = new AtomicLong();

	private ZstdDecoderPool() {}

	static InputStream open(InputStream source) {
		ZstdIncrementalFrameDecompressor decoder = IDLE.poll();
		if (decoder == null) {
			decoder = new ZstdIncrementalFrameDecompressor(MAX_WINDOW_BYTES);
		} else {
			IDLE_COUNT.decrementAndGet();
			IDLE_BYTES.addAndGet(-decoder.retainedWindowBytes());
		}
		return new PooledStream(new ZstdInputStream(source, decoder), decoder);
	}

	/** The number of idle decoders, for tests. */
	static int idle() {
		return IDLE_COUNT.get();
	}

	/** The window bytes the idle decoders hold, for tests. */
	static long idleBytes() {
		return IDLE_BYTES.get();
	}

	private static void giveBack(ZstdIncrementalFrameDecompressor decoder) {
		long retained = decoder.retainedWindowBytes();
		int count = IDLE_COUNT.incrementAndGet();
		long bytes = IDLE_BYTES.addAndGet(retained);
		if (count > MAX_IDLE || bytes > MAX_RETAINED_BYTES) {
			IDLE_COUNT.decrementAndGet();
			IDLE_BYTES.addAndGet(-retained);
			return;
		}
		IDLE.offer(decoder);
	}

	/**
	 * Hands the decoder back after close, and never while a read may still be using it. Both sides follow the same
	 * order: {@code close} sets {@code closed} and then checks {@code readers}, and a read registers in {@code readers}
	 * and then checks {@code closed}. So either the close sees the read and leaves the hand-back to it, or the read sees
	 * the close and never touches the decoder.
	 */
	private static final class PooledStream extends InputStream {
		private final ZstdInputStream delegate;
		private final ZstdIncrementalFrameDecompressor decoder;
		private final AtomicInteger readers = new AtomicInteger();
		private final AtomicBoolean released = new AtomicBoolean();
		private volatile boolean closed;

		PooledStream(ZstdInputStream delegate, ZstdIncrementalFrameDecompressor decoder) {
			this.delegate = delegate;
			this.decoder = decoder;
		}

		@Override
		public int read() throws IOException {
			enter();
			try {
				return delegate.read();
			} finally {
				exit();
			}
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			enter();
			try {
				return delegate.read(buffer, offset, length);
			} finally {
				exit();
			}
		}

		@Override
		public int available() throws IOException {
			enter();
			try {
				return delegate.available();
			} finally {
				exit();
			}
		}

		@Override
		public void close() throws IOException {
			closed = true;
			try {
				delegate.close();
			} finally {
				if (readers.get() == 0) release();
			}
		}

		private void enter() throws IOException {
			readers.incrementAndGet();
			if (closed) {
				exit();
				throw new IOException("Stream is closed");
			}
		}

		private void exit() {
			if (readers.decrementAndGet() == 0 && closed) release();
		}

		private void release() {
			if (!released.compareAndSet(false, true)) return;
			giveBack(decoder);
		}
	}
}
