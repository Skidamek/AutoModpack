package pl.skidam.automodpack.mixin.core;

import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Mixin;

/*? if >=1.20.2 {*/
import net.minecraft.network.FriendlyByteBuf;
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
/*?}*/

// ClientboundCustomQueryPacket exists on every version, so below 1.20.2 the body
// is simply disabled — the readPayload injection only exists from 1.20.2 —
// leaving an intentional no-op mixin.
@Mixin(value = ClientboundCustomQueryPacket.class, priority = 300)
public class LoginQueryRequestS2CPacketMixin {

	/*? if >=1.20.2 {*/
	@Shadow @Final private static int MAX_PAYLOAD_SIZE;

	@Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
	private static void readPayload(Identifier id, FriendlyByteBuf buf, CallbackInfoReturnable<CustomQueryPayload> cir) {
		if (id.getNamespace().equals(Constants.MOD_ID)) {
			cir.setReturnValue(new LoginRequestPayload(id, PayloadHelper.read(buf, MAX_PAYLOAD_SIZE)));
		}
	}
	/*?}*/
}
