package pl.skidam.automodpack_core.netty.message;

public abstract class ProtocolMessage {
    private final byte version; // 1 byte
    private final byte type; // 1 byte
    private final byte[] secret;   // 32 bytes

    public ProtocolMessage(byte version, byte type, byte[] secret) {
        if (secret.length != 32) {
            throw new IllegalArgumentException("Secret must be 32 bytes");
        }
        this.version = version;
        this.type = type;
        this.secret = secret;
    }

    public byte getVersion() {
        return version;
    }

    public byte getType() {
        return type;
    }

    public byte[] getSecret() {
        return secret;
    }
}
