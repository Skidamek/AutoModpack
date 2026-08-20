package pl.skidam.automodpack.mixin.core;

import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Mixin;

/*? if >=1.20.2 {*/
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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

	@WrapMethod(method = "readPayload")
	private static CustomQueryPayload readPayload(Identifier id, FriendlyByteBuf buf, Operation<CustomQueryPayload> original) {
		if (id.getNamespace().equals(Constants.MOD_ID)) return new LoginRequestPayload(id, PayloadHelper.read(buf, MAX_PAYLOAD_SIZE));
		return original.call(id, buf);
	}
	/*?}*/
}
