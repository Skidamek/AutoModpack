package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static pl.skidam.automodpack_core.utils.CustomFileUtils.isFilePhysical;

/**
 * A safe input stream that can read files currently locked or in-use by other processes (like active game logs).
 * <p>
 * It automatically applies a workaround for Windows file locking issues ("Access Denied") while using
 * standard, high-performance NIO on all other platforms.
 */
public class LockFreeInputStream extends InputStream {

    private final InputStream delegate;

    public LockFreeInputStream(Path path) throws IOException {
        if (PlatformUtils.IS_WIN && isFilePhysical(path)) {
            RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
            this.delegate = Channels.newInputStream(raf.getChannel());
        } else {
            this.delegate = Files.newInputStream(path, StandardOpenOption.READ);
        }
    }

    public static ReadableByteChannel openChannel(Path path) throws IOException {
        if (PlatformUtils.IS_WIN && isFilePhysical(path)) {
            return new RandomAccessFile(path.toFile(), "r").getChannel();
        } else {
            return Files.newByteChannel(path, StandardOpenOption.READ);
        }
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
        return delegate.available();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public synchronized void mark(int readlimit) {
        delegate.mark(readlimit);
    }

    @Override
    public synchronized void reset() throws IOException {
        delegate.reset();
    }

    @Override
    public boolean markSupported() {
        return delegate.markSupported();
    }
}