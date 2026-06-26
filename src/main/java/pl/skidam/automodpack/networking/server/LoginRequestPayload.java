package pl.skidam.automodpack.networking.server;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
/*? if <1.20.2 {*/
/*public record LoginRequestPayload(Identifier id, FriendlyByteBuf data) { }
*//*?} else {*/
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import pl.skidam.automodpack.networking.PayloadHelper;

public record LoginRequestPayload(Identifier id, FriendlyByteBuf data) implements CustomQueryPayload {
	@Override
	public void write(FriendlyByteBuf buf) {
		PayloadHelper.write(buf, data());
	}
}
/*?}*/
