package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;
import pl.skidam.automodpack.networking.ModPackets;

/*? if >= 1.20.5 {*/
import net.minecraft.client.network.CookieStorage;
/*?}*/

/*? if >=1.20.3 {*/
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
/*?} else {*/
/*import net.minecraft.client.gui.screen.ConnectScreen;
 *//*?}*/

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    /*? if >= 1.20.5 {*/
    @Inject(method = "connect(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;Lnet/minecraft/client/network/CookieStorage;)V", at = @At("HEAD"))
    public void onConnect(MinecraftClient client, ServerAddress address, ServerInfo info, CookieStorage cookieStorage, CallbackInfo ci) {
    /*?} else if > 1.19.2 {*/
    /*@Inject(method = "connect(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;)V", at = @At("HEAD"))
    public void onConnect(MinecraftClient client, ServerAddress address, ServerInfo info, CallbackInfo ci) {
    *//*?} else {*/
    /*@Inject(method = "connect(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;)V", at = @At("HEAD"))
    public void onConnect(MinecraftClient client, ServerAddress address, CallbackInfo ci) {
    *//*?}*/
        ModPackets.setOriginalServerAddress(InetSocketAddress.createUnresolved(address.getAddress(), address.getPort()));
    }
}
