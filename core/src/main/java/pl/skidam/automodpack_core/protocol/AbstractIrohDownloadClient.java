package pl.skidam.automodpack_core.protocol;

import dev.iroh.IrohBiStream;
import dev.iroh.IrohConnection;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.iroh.IrohPathSummary;
import pl.skidam.automodpack_core.protocol.iroh.IrohStreamAdapters;
import pl.skidam.automodpack_core.protocol.iroh.IrohTransportSupport;
import pl.skidam.automodpack_core.utils.PlatformUtils;

import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

abstract class AbstractIrohDownloadClient extends DownloadClient {
    private static final int MAX_PROTOCOL_ORIGINAL_FRAME_BYTES = MAX_CHUNK_SIZE;
    private static final int MAX_PROTOCOL_COMPRESSED_FRAME_BYTES = MAX_CHUNK_SIZE * 2;

    protected final ExecutorService executor;
    protected final Semaphore streamSlots;
    protected final byte compressionType;
    protected final int chunkSize = DEFAULT_CHUNK_SIZE;
    private final AtomicReference<String> lastLoggedPathSummary = new AtomicReference<>();

    protected AbstractIrohDownloadClient(int poolSize) {
        this.compressionType = PlatformUtils.canUseZstd() ? COMPRESSION_ZSTD : COMPRESSION_GZIP;
        this.streamSlots = new Semaphore(Math.max(1, poolSize));
        this.executor = Executors.newFixedThreadPool(Math.max(1, poolSize));
    }

    @Override
    public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
        return CompletableFuture.supplyAsync(
            () -> {
                acquireSlot();
                try (IrohBiStream stream = requireStream()) {
                    sendRequest(stream, FILE_REQUEST_TYPE, out -> {
                        out.writeInt(fileHash.length);
                        out.write(fileHash);
                    });
                    Path downloaded = readFileResponse(stream, destination, chunkCallback);
                    logSelectedPath("file download", downloaded);
                    return downloaded;
                } catch (IOException e) {
                    throw new CompletionException(e);
                } finally {
                    streamSlots.release();
                }
            },
            executor
        );
    }

    @Override
    public CompletableFuture<Path> requestRefresh(byte[][] fileHashes, Path destination) {
        return CompletableFuture.supplyAsync(
            () -> {
                acquireSlot();
                try (IrohBiStream stream = requireStream()) {
                    sendRequest(stream, REFRESH_REQUEST_TYPE, out -> {
                        out.writeInt(fileHashes.length);
                        if (fileHashes.length > 0) {
                            out.writeInt(fileHashes[0].length);
                            for (byte[] hash : fileHashes) {
                                out.write(hash);
                            }
                        }
                    });
                    Path downloaded = readFileResponse(stream, destination, null);
                    logSelectedPath("refresh download", downloaded);
                    return downloaded;
                } catch (IOException e) {
                    throw new CompletionException(e);
                } finally {
                    streamSlots.release();
                }
            },
            executor
        );
    }

    protected abstract IrohConnection requireConnection() throws IOException;

    protected void shutdownWorkers() {
        executor.shutdownNow();
    }

    private void sendRequest(IrohBiStream stream, byte type, ThrowingConsumer<DataOutputStream> payloadWriter) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION);
            out.writeByte(type);
            out.writeByte(compressionType);
            out.writeInt(chunkSize);
            payloadWriter.accept(out);
        }
        long written = stream.write(bytes.toByteArray());
        if (written <= 0) {
            throw new IOException("Failed to write iroh request");
        }
        if (!stream.finish()) {
            throw new IOException("Failed to finish iroh request stream");
        }
    }

    private Path readFileResponse(IrohBiStream stream, Path destination, IntConsumer chunkCallback) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(IrohStreamAdapters.input(stream, IrohTransportSupport.STREAM_READ_TIMEOUT_MS)))) {
            byte[] headerData = readProtocolMessageFrame(in);
            if (headerData.length < Byte.BYTES * 2) {
                throw new IOException("Invalid iroh response header");
            }
            ByteBuffer headerWrap = ByteBuffer.wrap(headerData);

            byte version = headerWrap.get();
            byte messageType = headerWrap.get();

            if (messageType == ERROR) {
                if (headerWrap.remaining() < Integer.BYTES) {
                    throw new IOException("Invalid iroh error frame");
                }
                int errLen = headerWrap.getInt();
                if (errLen < 0 || errLen > headerWrap.remaining()) {
                    throw new IOException("Invalid iroh error length: " + errLen);
                }
                byte[] errBytes = new byte[errLen];
                headerWrap.get(errBytes);
                throw new IOException("Server error: " + new String(errBytes, StandardCharsets.UTF_8));
            }

            if (messageType == END_OF_TRANSMISSION) {
                return destination;
            }

            if (messageType != FILE_RESPONSE_TYPE) {
                throw new IOException("Unexpected iroh response type: " + messageType);
            }

            if (headerWrap.remaining() < Long.BYTES) {
                throw new IOException("Invalid iroh file response header");
            }
            long expectedFileSize = headerWrap.getLong();
            if (expectedFileSize < 0) {
                throw new IOException("Invalid iroh response file size: " + expectedFileSize);
            }
            long receivedBytes = 0;

            try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                while (receivedBytes < expectedFileSize) {
                    byte[] dataFrame = readProtocolMessageFrame(in);
                    if (dataFrame.length == 0) {
                        throw new IOException("Unexpected empty iroh file frame");
                    }
                    int toWrite = Math.min(dataFrame.length, (int) (expectedFileSize - receivedBytes));
                    fos.write(dataFrame, 0, toWrite);
                    receivedBytes += toWrite;
                    if (chunkCallback != null) {
                        chunkCallback.accept(toWrite);
                    }
                }
            }

            byte[] eotData = readProtocolMessageFrame(in);
            if (eotData.length < 2 || eotData[0] != version || eotData[1] != END_OF_TRANSMISSION) {
                throw new IOException("Invalid end-of-transmission frame");
            }
            return destination;
        }
    }

    private byte[] readProtocolMessageFrame(DataInputStream in) throws IOException {
        int compressedLength = in.readInt();
        if (compressedLength < 0 || compressedLength > MAX_PROTOCOL_COMPRESSED_FRAME_BYTES) {
            throw new IOException("Invalid iroh compressed frame length: " + compressedLength);
        }
        int originalLength = in.readInt();
        if (originalLength < 0 || originalLength > MAX_PROTOCOL_ORIGINAL_FRAME_BYTES) {
            throw new IOException("Invalid iroh frame length: " + originalLength);
        }
        byte[] compressed = new byte[compressedLength];
        in.readFully(compressed);
        return responseCodec().decompress(compressed, 0, compressedLength, originalLength);
    }

    private IrohBiStream requireStream() throws IOException {
        IrohConnection.StreamOpenResult result = requireConnection().openBiResult(IrohTransportSupport.STREAM_OPEN_TIMEOUT_MS);
        if (result.isOpened()) {
            return result.getStream();
        }
        if (result.isTimeout()) {
            throw new IOException("Timed out while opening iroh stream");
        }
        throw new IOException("Failed to open iroh stream");
    }

    private void acquireSlot() {
        try {
            streamSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private void logSelectedPath(String operation, Path destination) {
        try {
            String pathSummary = IrohPathSummary.describe(requireConnection());
            String previousSummary = lastLoggedPathSummary.getAndSet(pathSummary);
            if (!pathSummary.equals(previousSummary)) {
                LOGGER.info("Iroh paths after {} for {}: {}", operation, destination, pathSummary);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to inspect iroh path after {}", operation, e);
        }
    }

    protected static String describePaths(IrohConnection connection) {
        return IrohPathSummary.describe(connection);
    }

    protected static String selectedPath(IrohConnection connection) {
        return IrohPathSummary.selectedOnly(connection);
    }

    private CompressionCodec responseCodec() {
        CompressionCodec codec = CompressionFactory.getCodec(compressionType);
        if (!codec.isInitialized()) {
            codec = CompressionFactory.getCodec(COMPRESSION_GZIP);
        }
        return codec;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws IOException;
    }
}
