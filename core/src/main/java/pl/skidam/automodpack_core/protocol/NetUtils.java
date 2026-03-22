package pl.skidam.automodpack_core.protocol;

public class NetUtils {

    // Magic numbers
    public static final int MAGIC_AMMH = 0x414D4D48;
    public static final int MAGIC_AMOK = 0x414D4F4B;
    public static final int MAGIC_AMID = 0x414D4944;

    // Protocol versions
    public static final byte LATEST_SUPPORTED_PROTOCOL_VERSION = 0x01;

    // Compression types
    public static final byte COMPRESSION_NONE = 0x00;
    public static final byte COMPRESSION_ZSTD = 0x01;
    public static final byte COMPRESSION_GZIP = 0x02;

    // Message types and configuration message types should not overlap
    // Message types
    public static final byte ECHO_TYPE = 0x00;
    public static final byte FILE_REQUEST_TYPE = 0x01;
    public static final byte FILE_RESPONSE_TYPE = 0x02;
    public static final byte REFRESH_REQUEST_TYPE = 0x03;
    public static final byte END_OF_TRANSMISSION = 0x04;
    public static final byte ERROR = 0x05;

    // Configuration message types
    public static final byte CONFIGURATION_ECHO_TYPE = 0x40;
    public static final byte CONFIGURATION_COMPRESSION_TYPE = 0x41;
    public static final byte CONFIGURATION_CHUNK_SIZE_TYPE = 0x42;

    // Chunk size
    public static final int DEFAULT_CHUNK_SIZE = 256 * 1024; // 256 KB
    public static final int MIN_CHUNK_SIZE = 8 * 1024; // 8 KB
    public static final int MAX_CHUNK_SIZE = 512 * 1024; // 512 KB
}
