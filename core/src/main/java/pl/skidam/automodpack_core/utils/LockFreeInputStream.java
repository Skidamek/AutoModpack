package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class LockFreeInputStream extends InputStream {

    private final InputStream delegate;

    public LockFreeInputStream(Path path) throws IOException {
        if (useRandomAccess(path)) {
            RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
            this.delegate = Channels.newInputStream(raf.getChannel());
        } else {
            this.delegate = Files.newInputStream(path, StandardOpenOption.READ);
        }
    }

    /**
     * Opens a FileChannel optimized for locked files.
     * <p>
     * Returns a concrete FileChannel.
     * This supports both Netty's ChunkedNioStream and zero-copy file transfers.
     */
    public static FileChannel openChannel(Path path) throws IOException {
        if (useRandomAccess(path)) {
            // RandomAccessFile.getChannel() returns a FileChannel
            return new RandomAccessFile(path.toFile(), "r").getChannel();
        } else {
            // FileChannel.open() returns a FileChannel
            return FileChannel.open(path, StandardOpenOption.READ);
        }
    }

    // Windows locks files aggressively. RandomAccessFile is often more lenient than NIO Files.newByteChannel.
    // We also ensure the file is on the default filesystem (not inside a Zip, etc).
    private static boolean useRandomAccess(Path path) {
        return PlatformUtils.IS_WIN && path.getFileSystem() == FileSystems.getDefault();
    }

    // ... Standard InputStream overrides below ...
    @Override public int read() throws IOException { return delegate.read(); }
    @Override public int read(byte[] b, int off, int len) throws IOException { return delegate.read(b, off, len); }
    @Override public int available() throws IOException { return delegate.available(); }
    @Override public void close() throws IOException { delegate.close(); }
    @Override public synchronized void mark(int readlimit) { delegate.mark(readlimit); }
    @Override public synchronized void reset() throws IOException { delegate.reset(); }
    @Override public boolean markSupported() { return delegate.markSupported(); }
}