package pl.skidam.automodpack.networking;

/*? if >=1.20.2 {*/
import pl.skidam.automodpack.networking.client.LoginResponsePayload;
import pl.skidam.automodpack.networking.server.LoginRequestPayload;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
/*?}*/

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.resources.ResourceLocation;

public class LoginQueryParser {
	public Packet<?> packet;
	public boolean success = true;
	public int queryId;
	public FriendlyByteBuf buf;
	public ResourceLocation channelName;

	public LoginQueryParser(Packet<?> packet) {
		if (packet instanceof ClientboundCustomQueryPacket packetS2C) {
			this.packet = packetS2C;
			/*? if <1.20.2 {*/
			/*this.queryId = packetS2C.getTransactionId();
			this.buf = packetS2C.getData();
		    this.channelName = packetS2C.getIdentifier();
            *//*?} else {*/
			this.queryId = packetS2C.transactionId();
			CustomQueryPayload payload = packetS2C.payload();
			if (payload instanceof LoginRequestPayload loginRequestPayload) {
				this.buf = loginRequestPayload.data();
				this.channelName = loginRequestPayload.id();
			}
			/*?}*/
		} else if (packet instanceof ServerboundCustomQueryAnswerPacket packetC2S) {
			this.packet = packetC2S;
			/*? if <1.20.2 {*/
            /*this.queryId = packetC2S.getTransactionId();
            this.buf = packetC2S.getData();
			*//*?} else {*/
			this.queryId = packetC2S.transactionId();
			CustomQueryAnswerPayload payload = packetC2S.payload();
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
