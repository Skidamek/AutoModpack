package pl.skidam.automodpack.mixin.core;

import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import pl.skidam.automodpack.networking.LoginNetworkingIDs;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.networking.server.ServerLoginNetworkAddon", remap = false)
public class FabricLoginMixin {

	@WrapMethod(method = "registerOutgoingPacket", remap = false)
	private void dontRemoveAutoModpackChannels(ClientboundCustomQueryPacket packet, Operation<Void> original) {
		/*? if <1.20.2 {*/
		/*Identifier id = packet.getIdentifier();
		*//*?} else {*/
		Identifier id = packet.payload().id();
		/*?}*/
		// Skip Fabric's registration only for AutoModpack channels.
		if (LoginNetworkingIDs.getByKey(id) == null) original.call(packet);
	}
}
