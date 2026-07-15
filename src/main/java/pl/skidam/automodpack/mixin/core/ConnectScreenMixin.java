package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack.networking.ModPackets;

/*? if >= 1.20.5 {*/
import net.minecraft.client.multiplayer.TransferState;
/*?}*/

/*? if > 1.19.3 {*/
import net.minecraft.client.multiplayer.ServerData;
/*?}*/

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    /*? if >= 1.20.5 {*/
    @Inject(method = "connect", at = @At("HEAD"))
    public void onConnect(Minecraft client, ServerAddress address, ServerData info, TransferState transferState, CallbackInfo ci) {
    /*?} else if > 1.19.3 {*/
    /*@Inject(method = "connect", at = @At("HEAD"))
    public void onConnect(Minecraft client, ServerAddress address, ServerData info, CallbackInfo ci) {
    *//*?} else {*/
    /*@Inject(method = "connect", at = @At("HEAD"))
    public void onConnect(Minecraft client, ServerAddress address, CallbackInfo ci) {
    *//*?}*/
        /*? if >= 1.20.5 {*/
        if (transferState == null || ModPackets.getOriginalServerAddress() == null) {
            ModPackets.setOriginalServerAddress(AddressHelpers.format(address.getHost(), address.getPort()));
        }
        /*?} else {*/
        /*ModPackets.setOriginalServerAddress(AddressHelpers.format(address.getHost(), address.getPort()));
        *//*?}*/
    }
}
