package pl.skidam.automodpack.networking.client;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

//#if MC < 1202
//$$     public record LoginResponsePayload(Identifier id, PacketByteBuf data) { }
//$$
//#else
import net.minecraft.network.packet.c2s.login.LoginQueryResponsePayload;

   public record LoginResponsePayload(Identifier id, PacketByteBuf data) implements LoginQueryResponsePayload {
       @Override
       public void write(PacketByteBuf buf) {
           buf.writeBytes(data().copy());
       }
   }
//#endif