package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;

import pl.skidam.automodpack.client.ClientHostMessage;
import pl.skidam.automodpack_core.client.SessionUpdateState;

/**
 * Join Game (play) is the one moment a world exists on every supported version: ≤1.20.1 carries
 * registries in that packet, 1.20.2+ has already finished configuration. Clearing here keeps one path.
 */
@Mixin(ClientPacketListener.class)
public class ClientPlayJoinMixin {
	@Inject(method = "handleLogin", at = @At("RETURN"))
	private void automodpack$worldEntered(CallbackInfo ci) {
		SessionUpdateState.worldEntered();
		ClientHostMessage.announcePendingFailure();
	}
}
