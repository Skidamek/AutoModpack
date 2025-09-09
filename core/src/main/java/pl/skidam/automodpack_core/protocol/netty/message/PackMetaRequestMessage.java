package pl.skidam.automodpack_core.protocol.netty.message;

import static pl.skidam.automodpack_core.protocol.NetUtils.*;

public class PackMetaRequestMessage extends ProtocolMessage {
    private final int dataLength;
    private final byte[] data;

    public PackMetaRequestMessage(byte version, byte[] secret, byte[] data) {
        super(version, PACK_META_REQUEST_TYPE, secret);
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