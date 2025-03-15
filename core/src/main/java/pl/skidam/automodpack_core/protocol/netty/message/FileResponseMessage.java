package pl.skidam.automodpack_core.protocol.netty.message;

import static pl.skidam.automodpack_core.protocol.NetUtils.FILE_RESPONSE_TYPE;

public class FileResponseMessage extends ProtocolMessage {
    private final int dataLength;
    private final byte[] data;

    public FileResponseMessage(byte version, byte[] secret, byte[] data) {
        super(version, FILE_RESPONSE_TYPE, secret);
        this.dataLength = data.length;
        this.data = data;
    }

    public int getDataLength() {
        return dataLength;
    }

    public byte[] getData() {
        return data;
    }
}
