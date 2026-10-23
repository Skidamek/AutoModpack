package pl.skidam.automodpack_core.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

class DownloadClientTest {

    private static final byte[] HASH = "hash".getBytes(StandardCharsets.UTF_8);

    @Test
    void aBodyWithinTheObjectSizeIsWritten(@TempDir Path directory) throws Exception {
        Path destination = directory.resolve("object");
        connection(fileResponse(5, "hello".getBytes(StandardCharsets.UTF_8))).sendDownloadFile(HASH, destination, 5, null).join();
        assertEquals("hello", Files.readString(destination));
    }

    @Test
    void aBodyDeclaredPastTheObjectSizeFailsBeforeTouchingTheDisk(@TempDir Path directory) throws Exception {
        Path destination = directory.resolve("object");
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> connection(fileResponse(Long.MAX_VALUE, new byte[0])).sendDownloadFile(HASH, destination, 5, null).join());
        assertInstanceOf(IOException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("declared"), thrown.getCause().getMessage());
        assertFalse(Files.exists(destination), "a refused body never reaches the disk");
    }

    @Test
    void anErrorLengthPastItsFrameFailsWithoutAllocatingIt(@TempDir Path directory) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(header);
        out.writeByte(PROTOCOL_VERSION);
        out.writeByte(ERROR);
        out.writeInt(Integer.MAX_VALUE);
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> connection(frame(header.toByteArray())).sendDownloadFile(HASH, directory.resolve("object"), 5, null).join());
        assertTrue(thrown.getCause().getMessage().contains("runs past its frame"), thrown.getCause().getMessage());
    }

    private static Connection connection(byte[] serverBytes) {
        return new Connection(new DataInputStream(new ByteArrayInputStream(serverBytes)), new DataOutputStream(new ByteArrayOutputStream()), new byte[32], COMPRESSION_NONE);
    }

    private static byte[] fileResponse(long declaredSize, byte[] body) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(header);
        out.writeByte(PROTOCOL_VERSION);
        out.writeByte(FILE_RESPONSE_TYPE);
        out.writeLong(declaredSize);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(frame(header.toByteArray()));
        if (body.length > 0) all.write(frame(body));
        all.write(frame(new byte[]{PROTOCOL_VERSION, END_OF_TRANSMISSION}));
        return all.toByteArray();
    }

    private static byte[] frame(byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(frame);
        out.writeInt(payload.length);
        out.writeInt(payload.length);
        out.write(payload);
        return frame.toByteArray();
    }
}
