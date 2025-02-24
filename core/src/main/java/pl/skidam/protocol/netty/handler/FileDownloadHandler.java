package pl.skidam.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.protocol.NetUtils.*;

public class FileDownloadHandler extends ChannelInboundHandlerAdapter {

    private enum State {
        WAITING_HEADER,
        RECEIVING_FILE,
        WAITING_EOT,
        COMPLETED,
        ERROR
    }

    private State state = State.WAITING_HEADER;
    private long expectedFileSize;
    private long receivedBytes = 0;
    private final Path destination;
    private FileOutputStream fos;
    private List<byte[]> rawFileData;
    private final CompletableFuture<Object> downloadFuture = new CompletableFuture<>();
    private byte protocolVersion = 0;

    public FileDownloadHandler(Path destination) {
        this.destination = destination;
    }

    public CompletableFuture<Object> getDownloadFuture() {
        return downloadFuture;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (destination != null) {
            fos = new FileOutputStream(destination.toFile());
        } else {
            rawFileData = new LinkedList<>();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (fos != null) {
            fos.close();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            LOGGER.warn("Received non-ByteBuf message: {}", msg);
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            // State machine to process the response
            if (state == State.WAITING_HEADER) {
                if (buf.readableBytes() < 10) {
                    return;
                }

                protocolVersion = buf.readByte();
                byte type = buf.readByte();
                if (type == ERROR) {
                    int errLen = buf.readInt();
                    byte[] errBytes = new byte[errLen];
                    buf.readBytes(errBytes);
                    downloadFuture.completeExceptionally(new IOException("Server error: " + new String(errBytes)));
                    state = State.ERROR;
                    return;
                }
                if (type != FILE_RESPONSE_TYPE) {
                    downloadFuture.completeExceptionally(new IOException("Unexpected message type: " + type));
                    state = State.ERROR;
                    return;
                }
                expectedFileSize = buf.readLong();
                state = State.RECEIVING_FILE;
            } else if (state == State.RECEIVING_FILE) {
                // In RECEIVING_FILE state, we write raw file data.
                int readable = buf.readableBytes();
                long remaining = expectedFileSize - receivedBytes;
                if (readable <= remaining) {
                    byte[] data = new byte[readable];
                    buf.readBytes(data);
                    if (fos != null) {
                        fos.write(data);
                    } else {
                        rawFileData.add(data);
                    }
                    receivedBytes += readable;
                } else {
                    // Read only the bytes that belong to the file.
                    byte[] data = new byte[(int) remaining];
                    buf.readBytes(data);
                    if (fos != null) {
                        fos.write(data);
                    } else {
                        rawFileData.add(data);
                    }
                    receivedBytes += remaining;
                    state = State.WAITING_EOT;
                }
                if (receivedBytes == expectedFileSize) {
                    state = State.WAITING_EOT;
                }
            } else if (state == State.WAITING_EOT) {
                if (buf.readableBytes() < 2) {
                    return;
                }

                byte ver = buf.readByte();
                if (ver != protocolVersion) {
                    downloadFuture.completeExceptionally(new IOException("Expected protocol version: " + protocolVersion + ", got: " + ver));
                    state = State.ERROR;
                    return;
                }
                byte type = buf.readByte();
                if (type != END_OF_TRANSMISSION) {
                    downloadFuture.completeExceptionally(new IOException("Expected EOT, got type: " + type));
                    state = State.ERROR;
                    return;
                }
                state = State.COMPLETED;
                Object result = destination != null ? destination : rawFileData;
                downloadFuture.complete(result);
                // Remove this handler now that download is complete.
                ctx.pipeline().remove(this);
            }
        } finally {
            ReferenceCountUtil.release(buf);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        downloadFuture.completeExceptionally(cause);
        ctx.close();
    }
}
