package pl.skidam.automodpack.networking;

/*? if <=1.19.2 {*/
/*import net.minecraft.network.Packet;
*//*?} else {*/
import net.minecraft.network.packet.Packet;
/*?}*/
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;
import net.minecraft.util.Identifier;
/*? if >=1.20.2 {*/
import pl.skidam.automodpack.networking.client.LoginResponsePayload;
import pl.skidam.automodpack.networking.server.LoginRequestPayload;
import net.minecraft.network.packet.c2s.login.LoginQueryResponsePayload;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestPayload;
/*?}*/

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class LoginQueryParser {
	public Packet<?> packet;
	public boolean success = true;
	public int queryId;
	public PacketByteBuf buf;
	public Identifier channelName;

	public LoginQueryParser(Packet<?> packet) {
		if (packet instanceof LoginQueryRequestS2CPacket packetS2C) {
			this.packet = packetS2C;
			/*? if <1.20.2 {*/
			/*this.queryId = packetS2C.getQueryId();
			this.buf = packetS2C.getPayload();
		    this.channelName = packetS2C.getChannel();
            *//*?} else {*/
			this.queryId = packetS2C.queryId();
			LoginQueryRequestPayload payload = packetS2C.payload();
			if (payload instanceof LoginRequestPayload loginRequestPayload) {
				this.buf = loginRequestPayload.data();
				this.channelName = loginRequestPayload.id();
			}
			/*?}*/
		} else if (packet instanceof LoginQueryResponseC2SPacket packetC2S) {
			this.packet = packetC2S;
			/*? if <1.20.2 {*/
            /*this.queryId = packetC2S.getQueryId();
            this.buf = packetC2S.getResponse();
			*//*?} else {*/
			this.queryId = packetC2S.queryId();
			LoginQueryResponsePayload payload = packetC2S.response();
			if (payload instanceof LoginResponsePayload loginRequestPayload) {
				this.buf = loginRequestPayload.data();
				this.channelName = loginRequestPayload.id();
			}
			/*?}*/
		} else {
			success = false;
            LOGGER.error("Invalid packet type: {}", packet.getClass().getName());
		}
	}
}
