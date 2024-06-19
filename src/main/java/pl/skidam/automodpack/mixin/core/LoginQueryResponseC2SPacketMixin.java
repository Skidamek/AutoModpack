package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
//#if MC >= 1202
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginQueryResponsePayload;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.skidam.automodpack.networking.LoginNetworkingIDs;
import pl.skidam.automodpack.networking.PayloadHelper;
import pl.skidam.automodpack.networking.client.LoginResponsePayload;

// TODO find better way to do this, its mixin only for 1.20.2 and above
@Mixin(value = LoginQueryResponseC2SPacket.class, priority = 300)
//#else
//$$ import pl.skidam.automodpack.init.Common;
//$$ @Mixin(Common.class)
//#endif
public class LoginQueryResponseC2SPacketMixin {
    //#if MC >= 1202
    @Shadow
    @Final
    private static int MAX_PAYLOAD_SIZE;

    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void readResponse(int queryId, PacketByteBuf buf, CallbackInfoReturnable<LoginQueryResponsePayload> cir) {
        Identifier automodpackID = LoginNetworkingIDs.getByValue(queryId);
        if (automodpackID == null) {
            return;
        }

        boolean hasPayload = buf.readBoolean();

        if (!hasPayload) {
            cir.setReturnValue(null);
            return;
        }

        cir.setReturnValue(new LoginResponsePayload(automodpackID, PayloadHelper.read(buf, MAX_PAYLOAD_SIZE)));
    }
    //#endif
}