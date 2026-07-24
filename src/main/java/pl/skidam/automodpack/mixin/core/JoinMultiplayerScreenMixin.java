package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ui.ModpackSelectionScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/**
 * Adds an "Optional Mods" button to the multiplayer screen's top footer row, beside Join Server /
 * Direct Connection / Add Server. The row is a centered LinearLayout, so adding a child lets the
 * game re-center all of them - no manual positioning. The button greys out, exactly like Edit and
 * Delete do, unless the highlighted server is a known AutoModpack modpack with optional groups.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

	@Shadow
	protected ServerSelectionList serverSelectionList;

	@Unique
	private Button automodpack$groupsButton;

	protected JoinMultiplayerScreenMixin(Component title) {
		super(title);
	}

	// Injected before the layout is walked into renderable widgets, capturing the top footer row so
	// our button is arranged and registered along with the vanilla ones. ordinal = 1 is that row
	// (0 is the outer vertical footer, 2 is the bottom row).
	@Inject(method = "init", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;visitWidgets(Ljava/util/function/Consumer;)V"))
	private void automodpack$addGroupsButton(CallbackInfo ci, @Local(ordinal = 1) LinearLayout topFooterButtons) {
		automodpack$groupsButton = topFooterButtons.addChild(Button.builder(VersionedText.translatable("automodpack.selection.button"), press -> {
			String address = automodpack$selectedServerAddress();
			if (address != null) minecraft.gui.setScreen(ModpackSelectionScreen.forServerAddress(this, address));
		}).width(100).build());
		automodpack$groupsButton.active = false;
	}

	// Fires whenever the highlighted server changes; require = 0 so a version without this exact
	// method simply leaves the button disabled instead of failing to load.
	@Inject(method = "onSelectedChange", at = @At("RETURN"), require = 0)
	private void automodpack$onSelectedChange(CallbackInfo ci) {
		if (automodpack$groupsButton == null) return;
		String address = automodpack$selectedServerAddress();
		automodpack$groupsButton.active = address != null && ModpackSelectionScreen.serverHasGroupsToConfigure(address);
	}

	@Unique
	private String automodpack$selectedServerAddress() {
		if (serverSelectionList == null) return null;
		ObjectSelectionList.Entry<?> selected = serverSelectionList.getSelected();
		if (selected instanceof ServerSelectionList.OnlineServerEntry onlineEntry) {
			ServerData data = onlineEntry.getServerData();
			if (data != null) return data.ip;
		}
		return null;
	}
}
