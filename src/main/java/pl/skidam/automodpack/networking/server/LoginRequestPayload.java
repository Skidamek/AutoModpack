package pl.skidam.automodpack.networking.server;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

//#if MC < 1202
//$$     public record LoginRequestPayload(Identifier id, PacketByteBuf data) { }
//$$
//#else
import net.minecraft.network.packet.s2c.login.LoginQueryRequestPayload;

   public record LoginRequestPayload(Identifier id, PacketByteBuf data) implements LoginQueryRequestPayload {
       @Override
       public void write(PacketByteBuf buf) {
           buf.writeBytes(data().copy());
       }
   }
//#endif