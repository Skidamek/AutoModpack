package pl.skidam.automodpack.mixin.core;

import java.net.InetSocketAddress;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.ServerAddressPin;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_loader_core.client.CertificateTrustStore;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/*? if >= 1.21.10 {*/
import net.minecraft.client.gui.screens.ManageServerScreen;
/*?} else {*/
/*import net.minecraft.client.gui.screens.EditServerScreen;
*//*?}*/

/*? if >= 1.21.10 {*/
@Mixin(ManageServerScreen.class)
/*?} else {*/
/*@Mixin(EditServerScreen.class)
*//*?}*/
public abstract class EditServerScreenMixin extends Screen {
	@Shadow
	private ServerData serverData;

	@Shadow
	private EditBox ipEdit;

	@Unique
	private boolean pinRemoveArmed;

	protected EditServerScreenMixin(Component title) {
		super(title);
	}

	@WrapMethod(method = "init")
	private void automodpack$addPinStatus(Operation<Void> original) {
		original.call();
		var origin = toOrigin(serverData.ip);
		var trust = CertificateTrustStore.get(origin);
		if (trust == null) return;
		pinRemoveArmed = false;

		String shortened = NetUtils.shortenFingerprint(trust.fingerprint);
		Component reason = switch (trust.reason) {
			case "ADDRESS_PIN" -> VersionedText.translatable("automodpack.pin.reason.address");
			case "SEED" -> VersionedText.translatable("automodpack.pin.reason.seed");
			default -> VersionedText.translatable("automodpack.pin.reason.tofu");
		};
		Button button = VersionedScreen.buttonWidget(width / 2 - 110, height / 2 + 105, 220, 20,
				VersionedText.translatable("automodpack.pin.active", reason, shortened), pressed -> {
					if (!pinRemoveArmed) {
						pinRemoveArmed = true;
						pressed.setMessage(VersionedText.translatable("automodpack.pin.removeConfirm"));
						return;
					}
					CertificateTrustStore.remove(origin);
					pressed.setMessage(VersionedText.translatable("automodpack.pin.removed"));
					pressed.active = false;
				});
		addRenderableWidget(button);
	}

	@WrapMethod(method = "onAdd")
	private void automodpack$rejectMalformedPin(Operation<Void> original) {
		ServerAddressPin.Parsed parsed = ServerAddressPin.parse(ipEdit.getValue());
		if (parsed.isMalformed()) {
			ScreenManager.failure(FailureRequest.of(new IllegalArgumentException(parsed.error()), "automodpack.pin.invalid", FailureCategory.SECURITY,
					FailureDestination.CURRENT_SCREEN, null));
			return;
		}
		original.call();
	}

	@ModifyExpressionValue(method = "onAdd", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;getValue()Ljava/lang/String;", ordinal = 1))
	private String automodpack$importPin(String address) {
		ServerAddressPin.Parsed parsed = ServerAddressPin.parse(address);
		if (parsed.hasPin()) {
			CertificateTrustStore.save(toOrigin(parsed.address()), parsed.fingerprint(), CertificateTrustStore.Reason.ADDRESS_PIN);
		}
		return parsed.address();
	}

	@Unique
	private static InetSocketAddress toOrigin(String address) {
		int metadata = address.indexOf('#');
		ServerAddress parsed = ServerAddress.parseString(metadata < 0 ? address : address.substring(0, metadata));
		return AddressHelpers.format(parsed.getHost(), parsed.getPort());
	}
}
