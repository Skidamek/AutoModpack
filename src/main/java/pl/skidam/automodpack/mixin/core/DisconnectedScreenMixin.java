package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.DisconnectedScreen;

import pl.skidam.automodpack.client.ScreenImpl;

/**
 * Reminds about a pending restart when a join dies before a world exists. Login, configuration, and
 * the first play packet all build this screen; a voluntary quit does not. The pending flag is cleared
 * when a world is entered, so an in-world kick stays quiet.
 */
@Mixin(DisconnectedScreen.class)
public class DisconnectedScreenMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void automodpack$remindPendingRestart(CallbackInfo ci) {
		ScreenImpl.updatePendingRestartToast();
	}
}
