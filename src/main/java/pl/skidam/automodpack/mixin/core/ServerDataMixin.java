package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.multiplayer.ServerData;

import pl.skidam.automodpack_core.auth.ServerAddressPin;

@Mixin(ServerData.class)
public abstract class ServerDataMixin {
	@Shadow
	public String ip;

	@ModifyExpressionValue(method = "write", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/ServerData;ip:Ljava/lang/String;"))
	private String automodpack$stripPinBeforeSave(String address) {
		return ip = ServerAddressPin.sanitize(address);
	}
}
