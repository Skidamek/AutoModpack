package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.auth.ServerAddressPin;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_loader_core.client.CertificateTrustStore;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/*? if >= 1.20.5 {*/
import net.minecraft.client.multiplayer.TransferState;
/*?}*/

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	/*? if >= 1.20.5 {*/
	@WrapMethod(method = "startConnecting")
	private static void onStartConnecting(Screen parent, Minecraft client, ServerAddress address, ServerData info, boolean quickPlay, TransferState transferState,
			Operation<Void> original) {
		if (transferState != null && ModPackets.getConnectionAttempt() != null) {
			original.call(parent, client, address, info, quickPlay, transferState);
			return;
		}
	/*?} else if > 1.19.3 {*/
	/*@WrapMethod(method = "startConnecting")
	private static void onStartConnecting(Screen parent, Minecraft client, ServerAddress address, ServerData info, boolean quickPlay, Operation<Void> original) {
	*//*?} else {*/
	/*@WrapMethod(method = "startConnecting")
	private static void onStartConnecting(Screen parent, Minecraft client, ServerAddress address, ServerData info, Operation<Void> original) {
	*//*?}*/
		ServerAddressPin.Parsed parsed = ServerAddressPin.parse(info.ip);
		info.ip = parsed.address();
		if (parsed.isMalformed()) {
			ScreenManager.failure(FailureRequest.of(new IllegalArgumentException(parsed.error()), "automodpack.pin.invalid", FailureCategory.SECURITY,
					FailureDestination.CURRENT_SCREEN, null));
			return;
		}

		var originAddress = AddressHelpers.format(address.getHost(), address.getPort());
		var savedTrust = CertificateTrustStore.get(originAddress);
		String expectedFingerprint = parsed.hasPin() ? parsed.fingerprint() : savedTrust == null ? null : savedTrust.fingerprint;
		String trustReason = parsed.hasPin() ? CertificateTrustStore.Reason.ADDRESS_PIN.name() : null;
		ModPackets.setConnectionAttempt(new ModPackets.ConnectionAttempt(originAddress, expectedFingerprint, trustReason));
	/*? if >= 1.20.5 {*/
		original.call(parent, client, address, info, quickPlay, transferState);
	/*?} else if >1.19.3 {*/
		/*original.call(parent, client, address, info, quickPlay);*/
	/*?} else {*/
		/*original.call(parent, client, address, info);*/
	/*?}*/
	}
}
