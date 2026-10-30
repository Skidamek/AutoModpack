package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ui.screen.LaunchApplyNotice;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/** A one-shot title-screen door to the changelog of a launch apply the player has not been shown yet. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@WrapMethod(method = "init")
	private void automodpack$addLaunchApplyNotice(Operation<Void> original) {
		original.call();
		if (!LaunchApplyNotice.pending()) return;
		int buttonWidth = Math.min(220, Math.max(64, width / 3));
		int buttonX = width - buttonWidth - 4;
		Button button = VersionedScreen.buttonWidget(buttonX, 8, buttonWidth, 20, VersionedText.text("automodpack.launchNotice.button"), press -> LaunchApplyNotice.open(this));
		addRenderableWidget(button);
	}
}
