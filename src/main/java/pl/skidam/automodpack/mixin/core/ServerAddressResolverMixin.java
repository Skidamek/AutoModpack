package pl.skidam.automodpack.mixin.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;

import pl.skidam.automodpack_core.utils.IpLiteralUtils;

@Mixin(ServerNameResolver.class)
public abstract class ServerAddressResolverMixin {
	@WrapOperation(method = "resolveAddress", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddressResolver;resolve(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"))
	private Optional<ResolvedServerAddress> preserveLiteralHost(ServerAddressResolver instance, ServerAddress address, Operation<Optional<ResolvedServerAddress>> original) {
		Optional<ResolvedServerAddress> resolved = original.call(instance, address);
		if (!IpLiteralUtils.isIpLiteral(address.getHost())) return resolved;
		return resolved.map(result -> autoModpack$withLiteralHost(address.getHost(), result));
	}

	@Unique
	private static ResolvedServerAddress autoModpack$withLiteralHost(String requestedHost, ResolvedServerAddress resolved) {
		InetSocketAddress socketAddress = resolved.asInetSocketAddress();
		InetAddress address = IpLiteralUtils.preserveLiteralHost(requestedHost, socketAddress.getAddress());
		return ResolvedServerAddress.from(new InetSocketAddress(address, socketAddress.getPort()));
	}
}
