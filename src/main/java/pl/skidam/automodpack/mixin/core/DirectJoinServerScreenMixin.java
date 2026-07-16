package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.gui.screens.DirectJoinServerScreen;

import pl.skidam.automodpack_core.auth.ServerAddressPin;

@Mixin(DirectJoinServerScreen.class)
public abstract class DirectJoinServerScreenMixin {
	@ModifyExpressionValue(method = "removed", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;getValue()Ljava/lang/String;"))
	private String automodpack$stripPinFromLastAddress(String address) {
		return ServerAddressPin.sanitize(address);
	}
}
