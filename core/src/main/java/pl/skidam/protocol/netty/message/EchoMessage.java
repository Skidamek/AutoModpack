package pl.skidam.protocol.netty.message;

import static pl.skidam.protocol.NetUtils.ECHO_TYPE;

public class EchoMessage extends ProtocolMessage {
    private final int dataLength;
    private final byte[] data;

    public EchoMessage(byte version, byte[] secret, byte[] data) {
        super(version, ECHO_TYPE, secret);
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
