package pl.skidam.automodpack.networking.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

/*? if <1.20.2 {*/
/*public record LoginResponsePayload(ResourceLocation id, FriendlyByteBuf data) { }
*//*?} else {*/
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import pl.skidam.automodpack.networking.PayloadHelper;

public record LoginResponsePayload(Identifier id, FriendlyByteBuf data) implements CustomQueryAnswerPayload {
    @Override
    public void write(FriendlyByteBuf buf) {
        PayloadHelper.write(buf, data());
    }
}
/*?}*/