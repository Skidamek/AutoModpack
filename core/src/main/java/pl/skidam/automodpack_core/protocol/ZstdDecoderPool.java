package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.airlift.compress.zstd.ZstdIncrementalFrameDecompressor;
import io.airlift.compress.zstd.ZstdInputStream;

/**
 * Lends zstd stream decoders so a client opening one {@link ZstdInputStream} per file reuses the ~400 KB of window,
 * entropy-table and input buffers instead of allocating them for every stream. A decoder serves exactly one open stream:
 * it is taken when the stream opens and comes back only once that stream is closed and no read is still running on it.
 * A stream that is never closed just keeps its decoder, which the GC then collects with it.
 *
 * <p>
 * A pooled decoder is built with {@link #MAX_WINDOW_BYTES} as its window, so it retains at most 2 x 1 MiB + 128 KiB of
 * window buffer however large a window a frame header declares. That is the window this protocol's own compressor
 * emits (single-segment frames up to 1 MiB, windowLog 20 above it, measured over 1 KiB..32 MiB inputs), so every stream
 * a pack sends decodes on a pooled decoder, and the idle pool holds at most ~17 MiB across {@link #MAX_IDLE} decoders.
 */
final class ZstdDecoderPool {
	static final int MAX_IDLE = 8;
	static final int MAX_WINDOW_BYTES = 1 << 20;

	private static final ConcurrentLinkedQueue<ZstdIncrementalFrameDecompressor> IDLE = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger IDLE_COUNT = new AtomicInteger();

	private ZstdDecoderPool() {}

	static InputStream open(InputStream source) {
		ZstdIncrementalFrameDecompressor decoder = IDLE.poll();
		if (decoder == null) {
			decoder = new ZstdIncrementalFrameDecompressor(MAX_WINDOW_BYTES);
		} else {
			IDLE_COUNT.decrementAndGet();
		}
		return new PooledStream(new ZstdInputStream(source, decoder), decoder);
	}

	/** The number of idle decoders, for tests. */
	static int idle() {
		return IDLE_COUNT.get();
	}

	private static void giveBack(ZstdIncrementalFrameDecompressor decoder) {
		if (IDLE_COUNT.incrementAndGet() > MAX_IDLE) {
			IDLE_COUNT.decrementAndGet();
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
