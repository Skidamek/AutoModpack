package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

import javax.net.ssl.SSLSocket;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.message.configuration.ConfigurationChunkSizeMessage;
import pl.skidam.automodpack_core.protocol.netty.message.configuration.ConfigurationCompressionMessage;
import pl.skidam.automodpack_core.protocol.netty.message.configuration.ConfigurationEchoMessage;
import pl.skidam.automodpack_core.utils.HashUtils;

/** One configured TLS connection to the modpack server: negotiates compression and chunk size, then serves serialized file downloads. */
class Connection implements AutoCloseable {

	private byte protocolVersion = LATEST_SUPPORTED_PROTOCOL_VERSION;
	// ZSTD stays the default on purpose: packs carry plenty of non-jar content (configs, scripts) that compresses well, and zstd costs a fraction of the transfer it saves.
	private CompressionType compressionType = CompressionType.ZSTD;
	private int chunkSize = DEFAULT_CHUNK_SIZE;
	private final byte[] secretBytes;
	private final SSLSocket socket;
	private final Socket transport;
	private final DataInputStream in;
	private final DataOutputStream out;
	private CompressionCodec compressionCodec;
	private final ProtocolFrameCodec.FrameScratch frameScratch = new ProtocolFrameCodec.FrameScratch();
	private final Executor executor;

	public Connection(SSLSocket socket, Socket transport, byte[] secretBytes, Executor executor) throws IOException {
		if (socket == null || socket.isClosed()) throw new IOException("Server connection is closed");
		if (transport != null && transport.isClosed()) throw new IOException("Server connection is closed");
		this.socket = socket;
		this.transport = transport;
		// Absence is absence on the wire too: a client with no secret sends the zero field the protocol shape reserves,
		// which the server rejects as unauthenticated. One zero array per connection, never per message.
		this.secretBytes = secretBytes == null ? new byte[Secrets.BYTE_LENGTH] : secretBytes;
		this.executor = executor;

		this.in = new DataInputStream(new BufferedInputStream(this.socket.getInputStream()));
		this.out = new DataOutputStream(new BufferedOutputStream(this.socket.getOutputStream()));

		if (!CompressionFactory.isAvailable(compressionType)) compressionType = CompressionType.GZIP;
		compressionType = sendCompressionConfig(compressionType);
		compressionCodec = CompressionFactory.createCodec(compressionType);
		chunkSize = sendChunkSizeConfig(DEFAULT_CHUNK_SIZE);
		sendEchoConfig();
	}

	public boolean isActive() {
		return !socket.isClosed() && (transport == null || !transport.isClosed());
	}

	private CompressionCodec getCompressionCodec() {
		return compressionCodec;
	}

	public CompletableFuture<Path> sendDownloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
		return sendDownloadFile(fileHash, destination, chunkCallback, null, 0, null);
	}

	/** Object request with an optional conditional hash and an inclusive range ({@code endInclusive} requires {@code offset} on the wire). */
	public CompletableFuture<Path> sendDownloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback, byte[] expectedSha1, long offset, Long endInclusive) {
		return sendRequest(fileHash, destination, expectedSha1, offset, endInclusive, false, chunkCallback).thenApply(fetch -> {
			if (fetch.unchanged()) throw new CompletionException(new IOException("Server answered UNCHANGED to an object request"));
			return fetch.path();
		});
	}

	/** Document request (reserved keys); a non-null expected hash may be answered with UNCHANGED instead of the document body. */
	public CompletableFuture<DocumentFetch> sendDownloadDocument(byte[] key, Path destination, byte[] expectedSha1, IntConsumer chunkCallback) {
		return sendRequest(key, destination, expectedSha1, 0, null, true, chunkCallback);
	}

	private CompletableFuture<DocumentFetch> sendRequest(byte[] fileHash, Path destination, byte[] expectedSha1, long offset, Long endInclusive, boolean document,
			IntConsumer chunkCallback) {
		if (destination == null) throw new IllegalArgumentException("Destination cannot be null");

		return CompletableFuture.supplyAsync(() -> {
			Exception exception = null;
			try {
				ByteArrayOutputStream baos = new ByteArrayOutputStream(64 + fileHash.length);
				DataOutputStream dos = new DataOutputStream(baos);
				dos.writeByte(protocolVersion);
				dos.writeByte(FILE_REQUEST_TYPE);
				dos.write(secretBytes);
				dos.writeInt(fileHash.length);
				dos.write(fileHash);
				byte flags = 0;
				if (expectedSha1 != null) flags |= FILE_REQUEST_EXPECTED_SHA1_FLAG;
				if (offset != 0 || endInclusive != null) flags |= FILE_REQUEST_OFFSET_FLAG;
				if (endInclusive != null) flags |= FILE_REQUEST_END_FLAG;
				dos.writeByte(flags);
				if (expectedSha1 != null) dos.write(expectedSha1);
				if ((flags & FILE_REQUEST_OFFSET_FLAG) != 0) dos.writeLong(offset);
				if ((flags & FILE_REQUEST_END_FLAG) != 0) dos.writeLong(endInclusive);

				writeProtocolMessage(baos.toByteArray());
				// A conditional document's body hash is the ground truth, so a host that streams instead of answering
				// UNCHANGED still reads as unchanged when the bytes match the expectation.
				return readFileResponse(destination, offset, expectedSha1 != null && document ? expectedSha1 : null, chunkCallback);
			} catch (Exception e) {
				exception = e;
				throw new CompletionException(e);
			} finally {
				finalBlock(exception);
			}
		}, executor);
	}

	private void finalBlock(Exception exception) {
		try {
			int available;
			while ((available = in.available()) > 0) {
				in.skipBytes(available);
			}
		} catch (IOException e) {
			if (exception == null) throw new CompletionException(e);
		}
	}

	private void writeProtocolMessage(byte[] payload) throws IOException {
		ProtocolFrameCodec.write(out, getCompressionCodec(), payload, chunkSize);
	}

	private ProtocolFrameCodec.Frame readProtocolMessageFrame() throws IOException {
		return ProtocolFrameCodec.read(in, getCompressionCodec(), chunkSize, frameScratch);
	}

	private DocumentFetch readFileResponse(Path destination, long offset, byte[] expectedSha1, IntConsumer chunkCallback) throws IOException {
		ProtocolFrameCodec.Frame header = readProtocolMessageFrame();
		ByteBuffer headerWrap = ByteBuffer.wrap(header.data(), 0, header.length());

		byte version = headerWrap.get();
		byte messageType = headerWrap.get();

		if (messageType == ERROR) {
			int errLen = headerWrap.getInt();
			byte[] errBytes = new byte[errLen];
			headerWrap.get(errBytes);
			// The trailing code byte is the machine-readable half of the error; a frame without one is generic.
			if (headerWrap.remaining() >= 1 && headerWrap.get() == ERROR_CODE_STALE_RANGE) throw new StaleRangeException();
			throw new IOException("Server error: " + new String(errBytes, StandardCharsets.UTF_8));
		}

		if (messageType == UNCHANGED_TYPE) return new DocumentFetch(null, true);

		if (messageType == END_OF_TRANSMISSION) return new DocumentFetch(destination, false);

		if (messageType != FILE_RESPONSE_TYPE) throw new IOException("Unexpected message type: " + messageType);

		long expectedFileSize = headerWrap.getLong();
		if (expectedFileSize < 0) throw new IOException("Negative file size: " + expectedFileSize);
		long receivedBytes = 0;
		// A ranged request's length is the remaining suffix, so it appends into the stored partial; a zero-length
		// answer to a range leaves the partial untouched, and an unconditional empty object still creates its file.
		MessageDigest hash = expectedSha1 == null ? null : HashUtils.newSha1Digest();
		if (expectedFileSize > 0 || offset == 0) {
			try (OutputStream fos = offset == 0 ? LocalFileWriter.open(destination) : LocalFileWriter.openAppending(destination)) {
				while (receivedBytes < expectedFileSize) {
					ProtocolFrameCodec.Frame dataFrame = readProtocolMessageFrame();
					int toWrite = ProtocolFrameCodec.writableFrameBytes(dataFrame.length(), expectedFileSize - receivedBytes);
					if (toWrite <= 0) throw new IOException("File frame did not advance the download");
					fos.write(dataFrame.data(), 0, toWrite);
					if (hash != null) hash.update(dataFrame.data(), 0, toWrite);
					receivedBytes += toWrite;
					if (chunkCallback != null) chunkCallback.accept(toWrite);
				}
			}
		}

		ProtocolFrameCodec.Frame eot = readProtocolMessageFrame();
		if (eot.length() < 2 || eot.data()[0] != version || eot.data()[1] != END_OF_TRANSMISSION) throw new IOException("Invalid EOT frame");
		if (hash != null && HexFormat.of().formatHex(hash.digest()).equals(new String(expectedSha1, StandardCharsets.UTF_8))) return new DocumentFetch(destination, true);
		return new DocumentFetch(destination, false);
	}

	private CompressionType sendCompressionConfig(CompressionType desiredCompression) throws IOException {
		writeAndFlush(new ConfigurationCompressionMessage(protocolVersion, desiredCompression).toBytes());

		byte version = readConfigResponseHeader(CONFIGURATION_COMPRESSION_TYPE);
		return ConfigurationCompressionMessage.readFrom(version, in).getCompressionType();
	}

	private int sendChunkSizeConfig(int desiredChunkSize) throws IOException {
		writeAndFlush(new ConfigurationChunkSizeMessage(protocolVersion, desiredChunkSize).toBytes());

		byte version = readConfigResponseHeader(CONFIGURATION_CHUNK_SIZE_TYPE);
		return ConfigurationChunkSizeMessage.readFrom(version, in).getChunkSize();
	}

	private void sendEchoConfig() throws IOException {
		writeAndFlush(new ConfigurationEchoMessage(protocolVersion).toBytes());
	}

	private void writeAndFlush(byte[] payload) throws IOException {
		out.write(payload);
		out.flush();
	}

	/** Reads and verifies the [version][type] header of one configuration reply; both ends ship together, so any version other than ours fails loudly. */
	private byte readConfigResponseHeader(byte expectedType) throws IOException {
		byte version = in.readByte();
		if (version != protocolVersion) throw new IOException("Protocol version mismatch: " + version);
		byte type = in.readByte();
		if (type != expectedType) throw new IOException("Unexpected response: " + type);
		return version;
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (Exception ignored) {
		}
	}
}
