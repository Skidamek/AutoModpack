package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.auth.ServerAddressPin;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_loader_core.client.CertificateTrustStore;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/*? if >= 1.20.5 {*/
import net.minecraft.client.multiplayer.TransferState;
/*?}*/

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	/*? if >= 1.20.5 {*/
	@Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
	private static void onStartConnecting(Screen parent, Minecraft client, ServerAddress address, ServerData info, boolean quickPlay, TransferState transferState,
			CallbackInfo ci) {
		if (transferState != null && ModPackets.getConnectionAttempt() != null) return;
	/*?} else if > 1.19.3 {*/
	/*@Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
	private static void onStartConnecting(Screen parent, Minecraft client, ServerAddress address, ServerData info, boolean quickPlay, CallbackInfo ci) {
	*//*?} else {*/
	/*@Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
	private static void onStartConnecting(Screen parent, Minecraft client, ServerAddress address, ServerData info, CallbackInfo ci) {
	*//*?}*/
		ServerAddressPin.Parsed parsed = ServerAddressPin.parse(info.ip);
		info.ip = parsed.address();
		if (parsed.isMalformed()) {
			new ScreenManager().error("automodpack.pin.invalid", parsed.error());
			ci.cancel();
			return;
		}

		var originAddress = AddressHelpers.format(address.getHost(), address.getPort());
		var savedTrust = CertificateTrustStore.get(originAddress);
		String fingerprint = parsed.hasPin() ? parsed.fingerprint() : savedTrust == null ? null : savedTrust.fingerprint;
		String reason = parsed.hasPin() ? CertificateTrustStore.Reason.ADDRESS_PIN.name() : null;
		ModPackets.setConnectionAttempt(new ModPackets.ConnectionAttempt(originAddress, fingerprint, reason));
	}
}
