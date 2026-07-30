package pl.skidam.automodpack.mixin.core;

/*? if <1.19.4 {*/
/*
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;

// LinearLayout / HeaderAndFooterLayout don't exist before 1.19.4, and this feature isn't worth a
// bespoke implementation against the old raw-widget-list screen API, so it's a no-op here.
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
	protected JoinMultiplayerScreenMixin(Component title) {
		super(title);
	}
}
*//*?} else {*/

/*? if >=26.2 {*/
import java.util.ArrayList;
import java.util.List;
/*?}*/

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ui.ModpackSelectionScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/**
 * Adds an "Optional Mods" button to the multiplayer screen's top footer row, beside Join Server /
 * Direct Connection / Add Server, reflecting whether the highlighted server is a known AutoModpack
 * modpack with optional groups.
 *
 * LinearLayout only gained a way to remove children in 26.2. On that version and above, the button
 * is added to / removed from the row on each selection change and the row re-centered, so it
 * appears and disappears cleanly. On every earlier version there is no child-removal API at all
 * (GridLayout also doesn't skip hidden children, so simply hiding it would leave a permanent gap),
 * so there the button is added to the row once, permanently, and toggled between active and
 * grayed-out instead.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

	@Unique
	private LinearLayout automodpack$topRow;
	/*? if >=26.2 {*/
	@Unique
	private final List<AbstractWidget> automodpack$vanillaRowButtons = new ArrayList<>();
	@Unique
	private boolean automodpack$buttonInRow = false;
	/*?}*/
	@Unique
	private Button automodpack$groupsButton;

	protected JoinMultiplayerScreenMixin(Component title) {
		super(title);
	}

	// Captured before the layout is walked into widgets. ordinal = 1 is the top footer row (0 is the
	// outer vertical footer, 2 the bottom row).
	@Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;visitWidgets(Ljava/util/function/Consumer;)V"))
	private void automodpack$captureRow(CallbackInfo ci, @Local(ordinal = 1) LinearLayout topFooterButtons) {
		automodpack$topRow = topFooterButtons;

		automodpack$groupsButton = Button.builder(VersionedText.translatable("automodpack.selection.button"), press ->
				minecraft.gui.setScreen(ModpackSelectionScreen.forSelectedModpack(this))).width(100).build();

		/*? if >=26.2 {*/
		automodpack$vanillaRowButtons.clear();
		topFooterButtons.visitChildren(child -> {
			if (child instanceof AbstractWidget widget) automodpack$vanillaRowButtons.add(widget);
		});
		automodpack$buttonInRow = false;
		automodpack$groupsButton.visible = false;
		addRenderableWidget(automodpack$groupsButton);
		/*?} else {*/
		/*
		automodpack$groupsButton.active = false;
		topFooterButtons.addChild(automodpack$groupsButton);
		*//*?}*/
	}

	// Fires on every selection change (and once at the end of init). require = 0 so a version without
	// this exact method just leaves the button in its default state rather than failing to load.
	@Inject(method = "onSelectedChange", at = @At("RETURN"), require = 0)
	private void automodpack$onSelectedChange(CallbackInfo ci) {
		if (automodpack$topRow == null || automodpack$groupsButton == null) return;

		boolean show = ModpackSelectionScreen.hasGroupsToConfigure();

		/*? if >=26.2 {*/
		if (show == automodpack$buttonInRow) return; // Row membership already correct; avoid needless relayout.

		automodpack$topRow.removeChildren();
		for (AbstractWidget widget : automodpack$vanillaRowButtons) automodpack$topRow.addChild(widget);
		if (show) automodpack$topRow.addChild(automodpack$groupsButton);

		automodpack$groupsButton.visible = show;
		automodpack$buttonInRow = show;
		this.repositionElements(); // safe here: only re-arranges the layout and resizes the list
		/*?} else {*/
		/*
		automodpack$groupsButton.active = show;
		*//*?}*/
	}
}
/*?}*/
