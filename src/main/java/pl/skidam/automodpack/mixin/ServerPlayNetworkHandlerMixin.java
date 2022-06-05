package pl.skidam.automodpack.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Shadow @Final
    private MinecraftServer server;

    @Shadow @Final private static Logger LOGGER;

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    public void onDisconnected(CallbackInfo ci) {
        LOGGER.error("Client disconnected");
    }
    @Inject(method = "onChatMessage", at = @At("HEAD"))
    public void onChatMessage(CallbackInfo ci) {
        LOGGER.error("Client sent chat message");
    }

}
