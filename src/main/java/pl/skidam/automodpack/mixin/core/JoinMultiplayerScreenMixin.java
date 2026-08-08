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

		int buttonWidth = Math.min(150, Math.max(1, width - 20));
		int buttonX = (width - buttonWidth) / 2;
		// Keep the action in the same centered lower control area as multiplayer's vanilla buttons.
		// The former top-right placement competed with the title and was easy to miss at small GUI scales.
		int buttonY = Math.max(32, height - 84);
		Button groupsButton = VersionedScreen.buttonWidget(buttonX, buttonY, buttonWidth, 20,
				VersionedText.translatable("automodpack.selection.button"), press -> ScreenImpl.setScreen(ModpackSelectionScreen.forSelectedModpack(this)));
		addRenderableWidget(groupsButton);
	}
}
