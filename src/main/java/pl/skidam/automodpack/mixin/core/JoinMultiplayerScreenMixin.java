package pl.skidam.automodpack.mixin.core;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ui.ModpackSelectionScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/**
 * Adds an "Optional Mods" button to the multiplayer screen's top footer row, beside Join Server /
 * Direct Connection / Add Server, but only while the highlighted server is a known AutoModpack
 * modpack with optional groups. The row is a centered LinearLayout whose GridLayout does not skip
 * hidden children, so simply toggling visibility would leave a gap; instead the row is rebuilt on
 * each selection change and re-centered, so the button appears and disappears cleanly.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

	@Shadow
	protected ServerSelectionList serverSelectionList;

	@Unique
	private LinearLayout automodpack$topRow;
	@Unique
	private final List<AbstractWidget> automodpack$vanillaRowButtons = new ArrayList<>();
	@Unique
	private Button automodpack$groupsButton;
	@Unique
	private boolean automodpack$buttonInRow = false;

	protected JoinMultiplayerScreenMixin(Component title) {
		super(title);
	}

	// Captured before the layout is walked into widgets. ordinal = 1 is the top footer row (0 is the
	// outer vertical footer, 2 the bottom row). The button is registered here but kept out of the row
	// until a matching server is selected.
	@Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;visitWidgets(Ljava/util/function/Consumer;)V"))
	private void automodpack$captureRow(CallbackInfo ci, @Local(ordinal = 1) LinearLayout topFooterButtons) {
		automodpack$topRow = topFooterButtons;
		automodpack$vanillaRowButtons.clear();
		topFooterButtons.visitChildren(child -> {
			if (child instanceof AbstractWidget widget) automodpack$vanillaRowButtons.add(widget);
		});
		automodpack$buttonInRow = false;

		automodpack$groupsButton = Button.builder(VersionedText.translatable("automodpack.selection.button"), press -> {
			String address = automodpack$selectedServerAddress();
			if (address != null) minecraft.gui.setScreen(ModpackSelectionScreen.forServerAddress(this, address));
		}).width(100).build();
		automodpack$groupsButton.visible = false;
		addRenderableWidget(automodpack$groupsButton);
	}

	// Fires on every selection change (and once at the end of init). require = 0 so a version without
	// this exact method just leaves the button hidden rather than failing to load.
	@Inject(method = "onSelectedChange", at = @At("RETURN"), require = 0)
	private void automodpack$onSelectedChange(CallbackInfo ci) {
		if (automodpack$topRow == null || automodpack$groupsButton == null) return;

		String address = automodpack$selectedServerAddress();
		boolean show = address != null && ModpackSelectionScreen.serverHasGroupsToConfigure(address);
		if (show == automodpack$buttonInRow) return; // Row membership already correct; avoid needless relayout.

		automodpack$topRow.removeChildren();
		for (AbstractWidget widget : automodpack$vanillaRowButtons) automodpack$topRow.addChild(widget);
		if (show) automodpack$topRow.addChild(automodpack$groupsButton);

		automodpack$groupsButton.visible = show;
		automodpack$buttonInRow = show;
		this.repositionElements(); // safe here: only re-arranges the layout and resizes the list
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
