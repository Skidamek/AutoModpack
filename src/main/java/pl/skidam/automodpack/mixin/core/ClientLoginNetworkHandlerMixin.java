package pl.skidam.automodpack.mixin.core;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;

import net.minecraft.client.Minecraft;
/*? if >=26.2 {*/
import net.minecraft.client.gui.Gui;
/*?}*/
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
/*? if >=1.21.4 {*/
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
/*?} else {*/
/*import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
*//*?}*/

import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.client.ClientLoginNetworkAddon;
import pl.skidam.automodpack.networking.client.IntentionalDisconnectControl;
import pl.skidam.automodpack.networking.packet.LoginSelfCheck;

@Mixin(value = ClientHandshakePacketListenerImpl.class, priority = 300)
public class ClientLoginNetworkHandlerMixin implements IntentionalDisconnectControl {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Unique
	private ClientLoginNetworkAddon autoModpack$addon;
	@Unique
	private final AtomicBoolean autoModpack$intentionalDisconnect = new AtomicBoolean();
	@Unique
	private final AtomicBoolean autoModpack$selfCheckFired = new AtomicBoolean();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void initAddon(CallbackInfo ci) {
		this.autoModpack$addon = new ClientLoginNetworkAddon((ClientHandshakePacketListenerImpl) (Object) this, this.minecraft);
	}

	@WrapMethod(method = "handleCustomQuery")
	private void handleQueryRequest(ClientboundCustomQueryPacket packet, Operation<Void> original) {
		if (this.autoModpack$addon == null || !this.autoModpack$addon.handlePacket(packet)) original.call(packet);
	}

	@WrapMethod(method = "handleCompression")
	private void autoModpack$selfCheckOnCompression(ClientboundLoginCompressionPacket packet, Operation<Void> original) {
		autoModpack$selfCheck();
		original.call(packet);
	}

	/*? if >=1.21.4 {*/
	@WrapMethod(method = "handleLoginFinished")
	private void autoModpack$selfCheckOnLoginSuccess(ClientboundLoginFinishedPacket packet, Operation<Void> original) {
		autoModpack$selfCheck();
		original.call(packet);
	}
	/*?} else {*/
	/*@WrapMethod(method = "handleGameProfile")
	private void autoModpack$selfCheckOnLoginSuccess(ClientboundGameProfilePacket packet, Operation<Void> original) {
		autoModpack$selfCheck();
		original.call(packet);
	}
	*//*?}*/

	/*
	 * Compression is the verdict point of the join: our addon holds the server's login and exchanges every query
	 * before the login finaliser sends compression (see ServerLoginNetworkHandlerMixin), so a compression packet
	 * means our queries either all arrived or all got swallowed. Login finished covers servers that never send one.
	 */
	@Unique
	private void autoModpack$selfCheck() {
		if (ModPackets.loginQueryArrived() || !autoModpack$selfCheckFired.compareAndSet(false, true)) return;
		LoginSelfCheck.maybeRun((ClientHandshakePacketListenerImpl) (Object) this);
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
