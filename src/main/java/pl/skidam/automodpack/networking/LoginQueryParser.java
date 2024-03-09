package pl.skidam.automodpack.networking;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;
import net.minecraft.util.Identifier;
//#if MC >= 1202
import pl.skidam.automodpack.networking.client.LoginResponsePayload;
import pl.skidam.automodpack.networking.server.LoginRequestPayload;
import net.minecraft.network.packet.c2s.login.LoginQueryResponsePayload;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestPayload;
//#endif

public class LoginQueryParser {
    public Packet<?> packet;
    public boolean success = true;
    public int queryId;
    public PacketByteBuf buf;
    public Identifier channelName;
    public LoginQueryParser(Packet<?> packet) {
        if (packet instanceof LoginQueryRequestS2CPacket packetS2C) {
            this.packet = packetS2C;
            //#if MC < 1202
//$$        this.queryId = packetS2C.getQueryId();
//$$        this.buf = packetS2C.getPayload();
//$$        this.channelName = packetS2C.getChannel();
//$$        this.success = true;
            //#else
            this.queryId = packetS2C.queryId();
            LoginQueryRequestPayload payload = packetS2C.payload();
            if (payload instanceof LoginRequestPayload loginRequestPayload) {
                this.buf = loginRequestPayload.data();
                this.channelName = loginRequestPayload.id();
            }
            //#endif
        } else if (packet instanceof LoginQueryResponseC2SPacket packetC2S) {
            this.packet = packetC2S;
            //#if MC < 1202
//$$        this.queryId = packetC2S.getQueryId();
//$$        this.buf = packetC2S.getResponse();
//$$        this.success = true;
            //#else
            this.queryId = packetC2S.queryId();
            LoginQueryResponsePayload payload = packetC2S.response();
            if (payload instanceof LoginResponsePayload loginRequestPayload) {
                this.buf = loginRequestPayload.data();
                this.channelName = loginRequestPayload.id();
            }
            //#endif
        } else {
            success = false;
            throw new IllegalArgumentException("Invalid packet type " + packet);
        }
    }
}
