package pl.skidam.automodpack.networking.server;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
/*? if <1.20.2 {*/
public record LoginRequestPayload(ResourceLocation id, FriendlyByteBuf data) { }
/*?} else {*/
/*import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import pl.skidam.automodpack.networking.PayloadHelper;

public record LoginRequestPayload(ResourceLocation id, FriendlyByteBuf data) implements CustomQueryPayload {
	@Override
	public void write(FriendlyByteBuf buf) {
		PayloadHelper.write(buf, data());
	}
}
*//*?}*/
