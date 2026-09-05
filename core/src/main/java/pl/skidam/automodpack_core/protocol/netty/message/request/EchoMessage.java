package pl.skidam.automodpack_core.protocol.netty.message.request;

import static pl.skidam.automodpack_core.protocol.NetUtils.ECHO_TYPE;

import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;

public class EchoMessage extends ProtocolMessage {
	private final byte[] data;

	public EchoMessage(byte version, byte[] secret, byte[] data) {
		super(version, ECHO_TYPE, secret);
		this.data = data;
	}

	public byte[] getData() {
		return data;
	}
}
