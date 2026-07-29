package pl.skidam.automodpack.mixin.core;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;

import net.minecraft.client.Minecraft;
/*? if >=26.2 {*/
import net.minecraft.client.gui.Gui;
/*?}*/
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;

import pl.skidam.automodpack.networking.client.ClientLoginNetworkAddon;
import pl.skidam.automodpack.networking.client.IntentionalDisconnectControl;

@Mixin(value = ClientHandshakePacketListenerImpl.class, priority = 300)
public class ClientLoginNetworkHandlerMixin implements IntentionalDisconnectControl {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Unique
	private ClientLoginNetworkAddon autoModpack$addon;
	@Unique
	private final AtomicBoolean autoModpack$intentionalDisconnect = new AtomicBoolean();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void initAddon(CallbackInfo ci) {
		this.autoModpack$addon = new ClientLoginNetworkAddon((ClientHandshakePacketListenerImpl) (Object) this, this.minecraft);
	}

	@Inject(method = "handleCustomQuery", at = @At(value = "HEAD"), cancellable = true)
	private void handleQueryRequest(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
		if (this.autoModpack$addon == null) return;

		if (this.autoModpack$addon.handlePacket(packet)) {
			// We have handled it, cancel vanilla behavior
			ci.cancel();
		}
	}

	@Override
	public void automodpack$markIntentionalDisconnect() {
		autoModpack$intentionalDisconnect.set(true);
	}

	/*? if >=26.2 {*/
	@WrapWithCondition(method = "onDisconnect", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
	private boolean autoModpack$suppressIntentionalDisconnectScreen(Gui gui, Screen screen) {
		return !autoModpack$intentionalDisconnect.getAndSet(false);
	}
	/*?} else {*/
	/*@WrapWithCondition(method = "onDisconnect", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
	private boolean autoModpack$suppressIntentionalDisconnectScreen(Minecraft minecraft, Screen screen) {
		return !autoModpack$intentionalDisconnect.getAndSet(false);
	}
	*//*?}*/
}
