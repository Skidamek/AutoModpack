package pl.skidam.automodpack.mixin.core;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.fabricmc.fabric.impl.networking.server.ServerLoginNetworkAddon;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import pl.skidam.automodpack.networking.LoginNetworkingIDs;

import java.util.Map;

@Pseudo
@Mixin(value = ServerLoginNetworkAddon.class, remap = false)
public class FabricLoginMixin {

    @WrapWithCondition(
            method = "registerOutgoingPacket",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
    )
    private boolean dontRemoveAutoModpackChannels(Map instance, Object key, Object value) {
        if (value instanceof Identifier id) {
            // If AutoModpack id, return false
            return LoginNetworkingIDs.getByKey(id) == null;
        }

        return true;
    }
}
