package pl.skidam.automodpack_core.protocol;

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

import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLSession;

import io.netty.buffer.ByteBuf;

import pl.skidam.mcholepunch.HolepunchConnection;
import pl.skidam.mcholepunch.HolepunchFailure;
import pl.skidam.mcholepunch.HolepunchHandler;

public class HolepunchSocket extends Socket {
	private volatile HolepunchConnection connection;
	private final HolepunchInputStream in;
	private volatile HolepunchOutputStream out;
	private volatile boolean closed;
	private volatile int soTimeoutMillis;
	private volatile TrafficCamouflage trafficCamouflage;
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
				feedReadData(bytes);
			}

			@Override
			public void onClosed(HolepunchFailure failure) {
				closed = true;
				in.feedEnd();
			}
		};
	}

	CompletionStage<Void> prepareTransportUpgrade() {
		HolepunchConnection activeConnection = connection;
		if (activeConnection == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("HolepunchSocket is not connected"));
		}
		return activeConnection.prepareTransportUpgrade();
	}

	CompletionStage<Void> commitTransportUpgrade() {
		HolepunchConnection activeConnection = connection;
		if (activeConnection == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("HolepunchSocket is not connected"));
		}
		return activeConnection.commitTransportUpgrade();
	}

	void enableTlsTrafficCamouflage(SSLSession session, boolean client) throws SSLKeyException {
		TlsRecordHeaderCamouflage.Pair tlsRecords = TlsRecordHeaderCamouflage.create(session, client);
		MinecraftFrameCamouflage.Pair minecraftFrames = MinecraftFrameCamouflage.create(session, client);
		trafficCamouflage = new TrafficCamouflage(tlsRecords, minecraftFrames);
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

	void feedReadData(byte[] data) {
		TrafficCamouflage camouflage = trafficCamouflage;
		if (camouflage != null && data.length != 0) {
			try {
				ByteBuffer input = ByteBuffer.wrap(data);
				ByteBuffer decodedFrames = ByteBuffer.allocate(data.length);
				camouflage.minecraftFrames().inbound().decode(input, decodedFrames);
				if (input.hasRemaining()) throw new IOException("Minecraft frame camouflage did not consume inbound data");
				decodedFrames.flip();
				ByteBuffer decodedTls = ByteBuffer.allocate(decodedFrames.remaining());
				camouflage.tlsRecords().inbound().transform(decodedFrames, decodedTls);
				if (decodedFrames.hasRemaining()) throw new IOException("TLS camouflage did not consume inbound data");
				decodedTls.flip();
				data = new byte[decodedTls.remaining()];
				decodedTls.get(data);
			} catch (IOException exception) {
				close();
				throw new IllegalStateException("Invalid camouflaged Minecraft/TLS stream", exception);
			}
		}
		in.feed(data);
	}

	private static class HolepunchInputStream extends InputStream {
		private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
		private byte[] current;
		private int offset;
		private volatile boolean end;
		private volatile int readTimeoutMillis;

		void setReadTimeoutMillis(int timeout) {
			readTimeoutMillis = timeout;
		}

		void feed(byte[] data) {
			if (data.length != 0 && !end) queue.offer(data);
		}

		void feedEnd() {
			end = true;
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
			ByteBuffer outbound = data.duplicate();
			TrafficCamouflage camouflage = trafficCamouflage;
			if (camouflage != null && outbound.hasRemaining()) {
				ByteBuffer encoded = ByteBuffer.allocate(outbound.remaining());
				camouflage.tlsRecords().outbound().transform(outbound, encoded);
				encoded.flip();
				outbound = encoded;
			}
			while (outbound.hasRemaining()) {
				int chunkLength = camouflage == null ? outbound.remaining() : Math.min(outbound.remaining(), MinecraftFrameCamouflage.MAX_FRAME_LENGTH);
				ByteBuffer chunk = outbound.slice();
				chunk.limit(chunkLength);
				ByteBuffer wire = chunk;
				if (camouflage != null) {
					ByteBuffer framed = ByteBuffer.allocate(MinecraftFrameCamouflage.HEADER_LENGTH + chunkLength);
					camouflage.minecraftFrames().outbound().encode(chunk, framed);
					framed.flip();
					wire = framed;
				}
				writeToConnection(activeConnection, wire);
				outbound.position(outbound.position() + chunkLength);
			}
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

	private record TrafficCamouflage(TlsRecordHeaderCamouflage.Pair tlsRecords, MinecraftFrameCamouflage.Pair minecraftFrames) {}
}
