package pl.skidam.automodpack_core.protocol.netty.message.request;

import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;

import static pl.skidam.automodpack_core.protocol.NetUtils.FILE_REQUEST_TYPE;

public class FileRequestMessage extends ProtocolMessage {
    private final int fileHashLength;
    private final byte[] fileHash;

    public FileRequestMessage(byte version, byte[] secret, byte[] fileHash) {
        super(version, FILE_REQUEST_TYPE, secret);
        this.fileHashLength = fileHash.length;
        this.fileHash = fileHash;
    }

    public int getFileHashLength() {
        return fileHashLength;
    }

    public byte[] getFileHash() {
        return fileHash;
    }
}
