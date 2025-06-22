package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
/*? if >=1.20.2 {*/
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.skidam.automodpack.networking.PayloadHelper;
import pl.skidam.automodpack.networking.server.LoginRequestPayload;
import pl.skidam.automodpack_core.GlobalVariables;

// TODO find better way to do this, its mixin only for 1.20.2 and above
@Mixin(value = ClientboundCustomQueryPacket.class, priority = 300)
/*?} else {*/
/*import pl.skidam.automodpack.init.Common;
@Mixin(Common.class)
*//*?}*/
public class LoginQueryRequestS2CPacketMixin {

/*? if >=1.20.2 {*/
    @Shadow @Final private static int MAX_PAYLOAD_SIZE;

    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void readPayload(ResourceLocation id, FriendlyByteBuf buf, CallbackInfoReturnable<CustomQueryPayload> cir) {
        if (id.getNamespace().equals(GlobalVariables.MOD_ID)) {
            cir.setReturnValue(new LoginRequestPayload(id, PayloadHelper.read(buf, MAX_PAYLOAD_SIZE)));
        }
    }
/*?}*/
}