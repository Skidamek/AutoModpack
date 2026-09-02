package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.ModpackSelectionScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/** Adds a modpack management button for the currently selected modpack to the multiplayer screen. */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

	protected JoinMultiplayerScreenMixin(Component title) {
		super(title);
	}

	@WrapMethod(method = "init")
	private void automodpack$addGroupsButton(Operation<Void> original) {
		original.call();
		if (!ModpackSelectionScreen.hasModpackManagement()) return;

		int titleLeft = (width - this.font.width(this.title)) / 2;
		int titleRight = titleLeft + this.font.width(this.title);
		int leftWidth = Math.max(0, titleLeft - 8);
		int rightWidth = Math.max(0, width - titleRight - 8);
		int buttonWidth = Math.min(110, Math.max(leftWidth, rightWidth));
		// Vanilla owns the entire lower 64-pixel footer and the server list starts immediately below
		// the title header. Use a side slot only when the complete button fits beside the title. At
		// very narrow widths neither side is safe, so omit the optional button instead of putting it
		// over the title or into vanilla's list/footer controls.
		if (buttonWidth < 64) return;
		boolean useRight = rightWidth >= leftWidth;
		int buttonX = useRight ? width - buttonWidth - 4 : 4;
		int buttonY = 8;
		Button groupsButton = VersionedScreen.buttonWidget(buttonX, buttonY, buttonWidth, 20,
				VersionedText.translatable(buttonWidth < 100 ? "automodpack.selection.shortButton" : "automodpack.selection.button"),
				press -> ScreenImpl.setScreen(ModpackSelectionScreen.forSelectedModpack(this)));
		addRenderableWidget(groupsButton);
	}
}
