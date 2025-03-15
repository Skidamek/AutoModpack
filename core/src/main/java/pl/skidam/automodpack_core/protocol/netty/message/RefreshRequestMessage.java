package pl.skidam.automodpack_core.protocol.netty.message;

import static pl.skidam.automodpack_core.protocol.NetUtils.REFRESH_REQUEST_TYPE;

public class RefreshRequestMessage extends ProtocolMessage {
    private final int fileHashesCount;
    private final int fileHashesLength;
    private final byte[][] fileHashesList;

    public RefreshRequestMessage(byte version, byte[] secret, byte[][] fileHashesList) {
        super(version, REFRESH_REQUEST_TYPE, secret);
        this.fileHashesCount = fileHashesList.length;
        this.fileHashesLength = fileHashesList[0].length;
        this.fileHashesList = fileHashesList;
    }

    public int getFileHashesCount() {
        return fileHashesCount;
    }

    public int getFileHashesLength() {
        return fileHashesLength;
    }

    public byte[][] getFileHashesList() {
        return fileHashesList;
    }
}
