package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.MAX_CHUNK_SIZE;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLKeyException;

import io.netty.buffer.ByteBuf;

import pl.skidam.mcholepunch.HolepunchConnection;
import pl.skidam.mcholepunch.HolepunchFailure;
import pl.skidam.mcholepunch.HolepunchHandler;

public class HolepunchSocket extends Socket {
	// Tripwire: one max protocol chunk. File requests never reach this; only a stalled consumer does.
	static final int MAX_QUEUED_READ_BYTES = MAX_CHUNK_SIZE;
	private volatile HolepunchConnection connection;
	private final HolepunchInputStream in;
	private volatile HolepunchOutputStream out;
	private volatile boolean closed;
	private volatile int soTimeoutMillis;
	private volatile TlsRecordCamouflage.Pair trafficCamouflage;
	private final Object writeLock = new Object();

	public HolepunchSocket(HolepunchConnection connection) {
		this.connection = Objects.requireNonNull(connection, "connection");
		this.in = new HolepunchInputStream();
		this.out = new HolepunchOutputStream();
	}

	public HolepunchSocket() {
		this.in = new HolepunchInputStream();
	}

	public synchronized void setConnection(HolepunchConnection connection) {
		if (closed) throw new IllegalStateException("HolepunchSocket is closed");
		this.connection = Objects.requireNonNull(connection, "connection");
		this.trafficCamouflage = null;
		this.out = new HolepunchOutputStream();
	}

	public HolepunchHandler handler() {
		return new HolepunchHandler() {
			@Override
			public void onRead(ByteBuffer data) {
				byte[] bytes = new byte[data.remaining()];
				data.get(bytes);
				in.feed(bytes);
			}

			@Override
			public void onRawRead(ByteBuffer data) {
				byte[] bytes = new byte[data.remaining()];
				data.get(bytes);
				feedCamouflagedReadData(bytes);
			}

			@Override
			public void onClosed(HolepunchFailure failure) {
				closed = true;
				in.feedEnd();
			}
		};
	}

	CompletionStage<Void> commitTransportUpgrade() {
		HolepunchConnection activeConnection = connection;
		if (activeConnection == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("HolepunchSocket is not connected"));
		}
		return activeConnection.commitTransportUpgrade();
	}

	void enableTlsTrafficCamouflage(boolean client) throws SSLKeyException {
		HolepunchConnection activeConnection = connection;
		if (activeConnection == null) throw new IllegalStateException("HolepunchSocket is not connected");
		trafficCamouflage = TlsRecordCamouflage.create(activeConnection.transportSecret(), client);
	}

	@Override
	public InputStream getInputStream() {
		return in;
	}

	@Override
	public OutputStream getOutputStream() {
		HolepunchOutputStream output = out;
		if (output == null) throw new IllegalStateException("HolepunchSocket is not connected");
		return output;
	}

	void writeBuffer(ByteBuf buffer) throws IOException {
		int readerIndex = buffer.readerIndex();
		int readableBytes = buffer.readableBytes();
		if (readableBytes == 0) return;
		if (buffer.nioBufferCount() == 1) {
			writeConnection(buffer.nioBuffer(readerIndex, readableBytes));
		} else if (buffer.nioBufferCount() > 1) {
			for (ByteBuffer nioBuffer : buffer.nioBuffers(readerIndex, readableBytes)) writeConnection(nioBuffer);
		} else {
			byte[] bytes = new byte[readableBytes];
			buffer.getBytes(readerIndex, bytes);
			writeConnection(ByteBuffer.wrap(bytes));
		}
	}

	@Override
	public synchronized void setSoTimeout(int timeout) throws SocketException {
		if (timeout < 0) throw new IllegalArgumentException("timeout cannot be negative");
		soTimeoutMillis = timeout;
		in.setReadTimeoutMillis(timeout);
	}

	@Override
	public synchronized int getSoTimeout() {
		return soTimeoutMillis;
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public boolean isConnected() {
		return connection != null && !closed;
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		try {
			HolepunchConnection activeConnection = connection;
			if (activeConnection != null) activeConnection.close();
		} finally {
			in.close();
			HolepunchOutputStream output = out;
			if (output != null) output.close();
		}
	}

	void feedPlainReadData(byte[] data) {
		in.feed(data);
	}

	void feedCamouflagedReadData(byte[] data) {
		TlsRecordCamouflage.Pair camouflage = trafficCamouflage;
		if (camouflage != null && data.length != 0) {
			try {
				ByteBuffer input = ByteBuffer.wrap(data);
				ByteBuffer decoded = ByteBuffer.allocate(data.length);
				camouflage.inbound().decode(input, decoded);
				if (input.hasRemaining()) throw new IOException("TLS record camouflage did not consume inbound data");
				decoded.flip();
				data = new byte[decoded.remaining()];
				decoded.get(data);
			} catch (IOException exception) {
				close();
				throw new IllegalStateException("Invalid camouflaged TLS record stream", exception);
			}
		}
		in.feed(data);
	}

	private class HolepunchInputStream extends InputStream {
		private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
		private final AtomicInteger queuedBytes = new AtomicInteger();
		private volatile boolean readsPaused;
		private byte[] current;
		private int offset;
		private volatile boolean end;
		private volatile int readTimeoutMillis;

		void setReadTimeoutMillis(int timeout) {
			readTimeoutMillis = timeout;
		}

		void feed(byte[] data) {
			if (data.length == 0 || end) return;
			queuedBytes.addAndGet(data.length);
			queue.offer(data);
			updateReadPause();
		}

		void feedEnd() {
			end = true;
		}

		private synchronized void updateReadPause() {
			HolepunchConnection activeConnection = connection;
			if (activeConnection == null) return;
			int queued = queuedBytes.get();
			if (queued >= MAX_QUEUED_READ_BYTES && !readsPaused) {
				readsPaused = true;
				activeConnection.pauseReads();
			} else if (queued <= MAX_QUEUED_READ_BYTES / 2 && readsPaused) {
				readsPaused = false;
				activeConnection.resumeReads();
			}
		}

		@Override
		public int read() throws IOException {
			byte[] b = new byte[1];
			int n = read(b, 0, 1);
			return n == -1 ? -1 : b[0] & 0xff;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			Objects.checkFromIndexSize(off, len, b.length);
			if (len == 0) return 0;

			while (current == null || offset >= current.length) {
				current = null;
				if (end && queue.isEmpty()) return -1;

				try {
					int timeout = readTimeoutMillis;
					if (timeout == 0) {
						current = queue.poll(100, TimeUnit.MILLISECONDS);
						if (current == null) continue;
					} else {
						current = queue.poll(timeout, TimeUnit.MILLISECONDS);
						if (current == null) {
							if (end && queue.isEmpty()) return -1;
							throw new SocketTimeoutException("Holepunch read timed out");
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("read interrupted", e);
				}
				offset = 0;
			}

			int n = Math.min(len, current.length - offset);
			System.arraycopy(current, offset, b, off, n);
			offset += n;
			queuedBytes.addAndGet(-n);
			updateReadPause();
			return n;
		}

		@Override
		public int available() {
			int available = current == null ? 0 : current.length - offset;
			for (byte[] queued : queue) available += queued.length;
			return available;
		}

		@Override
		public void close() {
			end = true;
		}
	}

	private class HolepunchOutputStream extends OutputStream {
		@Override
		public void write(int b) throws IOException {
			write(new byte[]{(byte) b}, 0, 1);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			Objects.checkFromIndexSize(off, len, b.length);
			if (len == 0) return;
			HolepunchSocket.this.writeConnection(ByteBuffer.wrap(b, off, len));
		}

		@Override
		public void close() {}
	}

	private void writeConnection(ByteBuffer data) throws IOException {
		synchronized (writeLock) {
			HolepunchConnection activeConnection = connection;
			if (activeConnection == null) throw new IOException("HolepunchSocket is not connected");
			// The camouflage applies exactly to the raw post-handoff stream era: bytes written
			// before the transport handoff completes stay plain TLS, bytes written after it are
			// framed with encrypted record headers. isRaw() flips once and never back, so the
			// sender-side decision matches the receiver's onRead/onRawRead split byte for byte.
			TlsRecordCamouflage.Pair camouflage = activeConnection.isRaw() ? trafficCamouflage : null;
			ByteBuffer outbound = data.duplicate();
			if (camouflage != null && outbound.hasRemaining()) {
				// Headroom receipt: the worst case is minimum-size records, 17 bytes of payload
				// becoming a 23-byte frame, about five percent.
				ByteBuffer encoded = ByteBuffer.allocate(outbound.remaining() + outbound.remaining() / 4 + 64);
				camouflage.outbound().encode(outbound, encoded);
				encoded.flip();
				outbound = encoded;
			}
			writeToConnection(activeConnection, outbound);
		}
	}

	private static void writeToConnection(HolepunchConnection connection, ByteBuffer data) throws IOException {
		try {
			connection.write(data).toCompletableFuture().get(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("write interrupted", e);
		} catch (Exception e) {
			throw new IOException("write failed", e);
		}
	}

}
