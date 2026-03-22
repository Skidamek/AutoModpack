package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohBiStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public final class IrohStreamAdapters {
    private IrohStreamAdapters() {
    }

    public static InputStream input(IrohBiStream stream, long timeoutMs) {
        return new InputStream() {
            private byte[] buffer = new byte[0];
            private int offset = 0;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int read = read(one, 0, 1);
                return read == -1 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                if (offset >= buffer.length) {
                    IrohBiStream.ReadResult result = stream.readTimeoutResult(Math.max(len, 8192), timeoutMs);
                    if (result.isTimeout()) {
                        throw new IOException("Timed out while reading iroh stream");
                    }
                    if (result.isError()) {
                        throw new IOException("Failed while reading iroh stream");
                    }
                    if (result.isEof()) {
                        return -1;
                    }
                    buffer = result.getData();
                    offset = 0;
                }
                int copyLen = Math.min(len, buffer.length - offset);
                System.arraycopy(buffer, offset, b, off, copyLen);
                offset += copyLen;
                if (offset >= buffer.length) {
                    buffer = new byte[0];
                    offset = 0;
                }
                return copyLen;
            }
        };
    }

    public static OutputStream output(IrohBiStream stream) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] { (byte) b });
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                byte[] payload = off == 0 && len == b.length ? b : Arrays.copyOfRange(b, off, off + len);
                long written = stream.write(payload);
                if (written != len) {
                    throw new IOException("Failed to write to iroh stream");
                }
            }
        };
    }
}
