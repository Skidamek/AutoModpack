package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.multiplayer.resolver.ServerAddress;

import pl.skidam.automodpack_core.auth.ServerAddressPin;

@Mixin(ServerAddress.class)
public abstract class ServerAddressMixin {
	@ModifyVariable(method = {"parseString(Ljava/lang/String;)Lnet/minecraft/client/multiplayer/resolver/ServerAddress;",
			"isValidAddress(Ljava/lang/String;)Z"}, at = @At("HEAD"), argsOnly = true, require = 2)
	private static String automodpack$stripPin(String address) {
		return ServerAddressPin.strip(address);
	}
}
