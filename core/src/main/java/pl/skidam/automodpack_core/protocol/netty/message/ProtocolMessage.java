package pl.skidam.automodpack_core.protocol.netty.message;

import java.util.Arrays;

import pl.skidam.automodpack_core.auth.Secrets;

public abstract class ProtocolMessage {
	private final byte version; // 1 byte
	private final byte type; // 1 byte
	private final byte[] secret; // 32 bytes

	public ProtocolMessage(byte version, byte type, byte[] secret) {
		if (secret.length != Secrets.BYTE_LENGTH) throw new IllegalArgumentException("Secret must be " + Secrets.BYTE_LENGTH + " bytes");
		this.version = version;
		this.type = type;
		this.secret = Arrays.copyOf(secret, secret.length);
	}

	public byte getVersion() {
		return version;
	}

	public byte getType() {
		return type;
	}

	public byte[] getSecret() {
		return Arrays.copyOf(secret, secret.length);
	}
}
