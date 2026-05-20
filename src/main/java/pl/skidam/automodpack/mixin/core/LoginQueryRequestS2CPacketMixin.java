package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;

/*? if >=1.20.2 {*/
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.skidam.automodpack.networking.PayloadHelper;
import pl.skidam.automodpack.networking.server.LoginRequestPayload;
import pl.skidam.automodpack_core.Constants;

@Mixin(value = ClientboundCustomQueryPacket.class, priority = 300)
public class LoginQueryRequestS2CPacketMixin {

    @Shadow @Final private static int MAX_PAYLOAD_SIZE;

    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void readPayload(Identifier id, FriendlyByteBuf buf, CallbackInfoReturnable<CustomQueryPayload> cir) {
        if (id.getNamespace().equals(Constants.MOD_ID)) {
            cir.setReturnValue(new LoginRequestPayload(id, PayloadHelper.read(buf, MAX_PAYLOAD_SIZE)));
        }
    }
}
/*?} else {*/
/*import net.minecraft.core.BlockPos;

@Mixin(BlockPos.class)
public class LoginQueryRequestS2CPacketMixin {
    // No-op: this mixin is only needed for 1.20.2+ readPayload injection
}
*//*?}*/