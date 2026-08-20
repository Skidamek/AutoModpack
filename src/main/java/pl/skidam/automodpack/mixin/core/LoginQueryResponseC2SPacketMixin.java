package pl.skidam.automodpack.mixin.core;

import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import org.spongepowered.asm.mixin.Mixin;

/*? if >=1.20.2 {*/
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import pl.skidam.automodpack.networking.LoginNetworkingIDs;
import pl.skidam.automodpack.networking.PayloadHelper;
import pl.skidam.automodpack.networking.client.LoginResponsePayload;
/*?}*/

// Below 1.20.2 the stonecutter replacement rewrites the target to its old name
// (ServerboundCustomQueryAnswerPacket) and the body is disabled — the readPayload
// injection only exists from 1.20.2 — leaving an intentional no-op mixin.
@Mixin(value = ServerboundCustomQueryAnswerPacket.class, priority = 300)
public class LoginQueryResponseC2SPacketMixin {

	/*? if >=1.20.2 {*/
	@Shadow
	@Final
	private static int MAX_PAYLOAD_SIZE;

	@WrapMethod(method = "readPayload")
	private static CustomQueryAnswerPayload readResponse(int queryId, FriendlyByteBuf buf, Operation<CustomQueryAnswerPayload> original) {
		Identifier automodpackID = LoginNetworkingIDs.getByValue(queryId);
		if (automodpackID == null) {
			return original.call(queryId, buf);
		}

		boolean hasPayload = buf.readBoolean();

		if (!hasPayload) {
			return null;
		}

		return new LoginResponsePayload(automodpackID, PayloadHelper.read(buf, MAX_PAYLOAD_SIZE));
	}
	/*?}*/
}
