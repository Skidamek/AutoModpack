package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.multiplayer.resolver.ServerAddress;

import pl.skidam.automodpack_core.auth.ServerAddressPin;

@Mixin(ServerAddress.class)
public abstract class ServerAddressMixin {
	@WrapMethod(method = "parseString(Ljava/lang/String;)Lnet/minecraft/client/multiplayer/resolver/ServerAddress;")
	private static ServerAddress automodpack$stripPin(String address, Operation<ServerAddress> original) {
		return original.call(ServerAddressPin.strip(address));
	}

	@WrapMethod(method = "isValidAddress(Ljava/lang/String;)Z")
	private static boolean automodpack$stripPinForValidation(String address, Operation<Boolean> original) {
		return original.call(ServerAddressPin.strip(address));
	}
}
