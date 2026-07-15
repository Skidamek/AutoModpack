package pl.skidam.automodpack_core.protocol.netty.message.request;

import static pl.skidam.automodpack_core.protocol.NetUtils.ECHO_TYPE;

import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;

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
