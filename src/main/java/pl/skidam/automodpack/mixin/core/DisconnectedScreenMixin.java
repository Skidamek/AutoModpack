package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.DisconnectedScreen;

import pl.skidam.automodpack.client.ScreenImpl;

/**
 * Reminds about a pending restart whenever the game builds a disconnect screen: a server kick in any
 * phase is the one moment the reminder answers, and a voluntary quit never constructs one. Injecting
 * the screen instead of the per-phase disconnect listeners keeps one target that resolves on every
 * supported version, including the ones without a configuration phase.
 */
@Mixin(DisconnectedScreen.class)
public class DisconnectedScreenMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void automodpack$remindPendingRestart(CallbackInfo ci) {
		ScreenImpl.updatePendingRestartToast();
	}
}
