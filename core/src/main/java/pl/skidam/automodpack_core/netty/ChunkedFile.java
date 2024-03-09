package pl.skidam.automodpack_core.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import pl.skidam.automodpack_core.callbacks.Callback;

import java.io.IOException;
import java.io.RandomAccessFile;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class ChunkedFile {
    private final RandomAccessFile file;
    private final long startOffset;
    private final long endOffset;
    private final long chunkSize;
    private long offset;

    ChunkedFile(RandomAccessFile file, long offset, long length, int chunkSize) throws IOException {
        this.file = file;
        this.offset = startOffset = offset;
        this.endOffset = offset + length;
        this.chunkSize = chunkSize;

        file.seek(offset);
    }

    private boolean isClosed(ChannelHandlerContext context) {
        return !context.pipeline().firstContext().channel().isActive();
    }

    public void readAllWriteAndFlush(ChannelHandlerContext context, Callback callback) throws Exception {
        ByteBufAllocator allocator = context.alloc();

        int slices = (int) Math.ceil((double) (length() / chunkSize));

        // Write to channel in slices
        if (slices > 0) {
            for (int i = 0; i < slices; i++) {

                if (isClosed(context)) {
                    return;
                }

                ByteBuf buf = readChunk(allocator);
                context.pipeline().firstContext().write(buf).addListener(future -> {
                    if (!future.isSuccess() && !isClosed(context)) {
                        LOGGER.error("Writing to channel error! " + future.cause() + " " + future.cause().getMessage());
                    }
                });
                buf.clear();
            }
        }

        if (isClosed(context)) {
            callback.run();
            return;
        }

        // Write to channel the last chunk
        ByteBuf buf = readChunk(allocator);
        ChannelFuture channelFuture = context.pipeline().firstContext().writeAndFlush(buf);
        buf.clear();
        channelFuture.addListener(future -> {
            try {
                if (!future.isSuccess() && !isClosed(context)) {
                    LOGGER.error("Writing and flushing channel error! " + future.cause() + " " + future.cause().getMessage());
                }

                channelFuture.channel().close().addListener(closeFuture -> {
                    if (!future.isSuccess() && !isClosed(context)) {
                        LOGGER.error("Closing channel error " + closeFuture.cause() + " " + closeFuture.cause().getMessage());
                    }
                });
            } finally {
                callback.run();
            }
        });
    }


    // FIXME something here is messed up with these buffers... it allocates too much memory and doesnt release it that often/fast ... as should
    public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
        long offset = this.offset;
        if (offset >= endOffset) {
            return null;
        }

        int chunkSize = (int) Math.min(this.chunkSize, endOffset - offset);
        // Check if the buffer is backed by an byte array. If so we can optimize it a bit an safe a copy

        ByteBuf buffer = allocator.heapBuffer(chunkSize);
        LOGGER.info("Allocated buffer: " + buffer.capacity() + " bytes");

        boolean release = true;
        try {
            file.readFully(buffer.array(), buffer.arrayOffset(), chunkSize);
            buffer.writerIndex(chunkSize);
            this.offset = offset + chunkSize;
            release = false;
            return buffer;
        } finally {
            if (release) {
                buffer.release();
            }
        }
    }

    public long length() {
        return endOffset - startOffset;
    }

    public long progress() {
        return offset - startOffset;
    }
}