package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
//#if MC >= 1202
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestPayload;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.skidam.automodpack.networking.PayloadHelper;
import pl.skidam.automodpack.networking.server.LoginRequestPayload;
import pl.skidam.automodpack_core.GlobalVariables;

// TODO find better way to do this, its mixin only for 1.20.2 and above
@Mixin(value = LoginQueryRequestS2CPacket.class, priority = 300)
//#else
//$$ import pl.skidam.automodpack.init.Common;
//$$ @Mixin(Common.class)
//#endif
public class LoginQueryRequestS2CPacketMixin {

    //#if MC >= 1202
    @Shadow @Final private static int MAX_PAYLOAD_SIZE;

    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void readPayload(Identifier id, PacketByteBuf buf, CallbackInfoReturnable<LoginQueryRequestPayload> cir) {
        if (id.getNamespace().equals(GlobalVariables.MOD_ID)) {
            cir.setReturnValue(new LoginRequestPayload(id, PayloadHelper.read(buf, MAX_PAYLOAD_SIZE)));
        }
    }
    //#endif
}