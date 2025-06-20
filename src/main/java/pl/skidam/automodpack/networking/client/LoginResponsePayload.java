package pl.skidam.automodpack.networking.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.resources.ResourceLocation;
import pl.skidam.automodpack.networking.PayloadHelper;

public record LoginResponsePayload(ResourceLocation id, FriendlyByteBuf data) implements CustomQueryAnswerPayload {
    @Override
    public void write(FriendlyByteBuf buf) {
        PayloadHelper.write(buf, data());
    }
}
/*}*/