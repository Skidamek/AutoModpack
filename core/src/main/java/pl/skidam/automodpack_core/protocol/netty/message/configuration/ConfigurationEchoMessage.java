package pl.skidam.automodpack_core.protocol.netty.message.configuration;

import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_ECHO_TYPE;

import pl.skidam.automodpack_core.protocol.netty.message.ConfigurationMessage;

/** The configuration-exchange terminator: a payloadless [version][0x40] frame that fixes the protocol version. */
public class ConfigurationEchoMessage extends ConfigurationMessage {

	public ConfigurationEchoMessage(byte version) {
		super(version, CONFIGURATION_ECHO_TYPE);
	}
}
