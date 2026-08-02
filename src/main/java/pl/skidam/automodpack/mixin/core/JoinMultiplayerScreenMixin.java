package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

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

		Button groupsButton = VersionedScreen.buttonWidget(Math.max(5, width - 105), 6, 100, 20,
				VersionedText.translatable("automodpack.selection.button"), press -> minecraft.gui.setScreen(ModpackSelectionScreen.forSelectedModpack(this)));
		addRenderableWidget(groupsButton);
	}
}
