package pl.skidam.automodpack.mixin.core;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginNetworkHandlerAccessor {
    /*? if > 1.21.5 {*/
    /*@Accessor("gameProfile")
    *//*?} else {*/
    @Accessor("authenticatedProfile")
    /*?}*/
    GameProfile getGameProfile();

    @Accessor
    Connection getConnection();

    @Accessor
    MinecraftServer getServer();
}
