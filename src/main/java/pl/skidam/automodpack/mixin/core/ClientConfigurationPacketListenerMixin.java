package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import pl.skidam.automodpack.client.ScreenImpl;

/**
 * Reminds about a pending restart when the server kicks the client during the configuration phase, where mod-loader
 * channel mismatches land. The target class exists only on 1.20.2+, so the string target keeps older versions
 * compiling; there the class never loads and this mixin stays inert. The argument-less capture rides out the
 * disconnect payload changing shape across versions, and the play phase is deliberately untouched so voluntary
 * quits never toast.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl")
public class ClientConfigurationPacketListenerMixin {
	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void automodpack$remindPendingRestart(CallbackInfo ci) {
		ScreenImpl.updatePendingRestartToast();
	}
}
