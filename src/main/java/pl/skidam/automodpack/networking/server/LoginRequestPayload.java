package pl.skidam.automodpack.networking.server;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/*? if <1.20.2 {*/
/*public record LoginRequestPayload(Identifier id, PacketByteBuf data) { }
*//*?} else {*/
import net.minecraft.network.packet.s2c.login.LoginQueryRequestPayload;
import pl.skidam.automodpack.networking.PayloadHelper;

public record LoginRequestPayload(Identifier id, PacketByteBuf data) implements LoginQueryRequestPayload {
	@Override
	public void write(PacketByteBuf buf) {
		PayloadHelper.write(buf, data());
	}
}
/*?}*/
