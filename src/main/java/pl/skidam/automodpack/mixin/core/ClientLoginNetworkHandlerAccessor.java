package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;

@Mixin(ClientHandshakePacketListenerImpl.class)
public interface ClientLoginNetworkHandlerAccessor {
	@Accessor("connection")
	Connection getConnection();
}
