package pl.skidam.automodpack.mixin.dev;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

import static pl.skidam.automodpack_core.GlobalVariables.LOADER_MANAGER;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

@Mixin(TitleScreen.class)
public class TestButton extends Screen {

    protected TestButton(Component title) {
        super(title);
    }

    @Inject(
            method = "init",
            at = @At("HEAD")
    )
    private void init(CallbackInfo ci) {

        // check if we are in dev environment
        if (!LOADER_MANAGER.isDevelopmentEnvironment()) {
            return;
        }

/*? if >=1.17 {*/
        this.addRenderableWidget(
/*?} else {*//*
   this.addButton(
*//*?}*/
                VersionedScreen.buttonWidget(
                        this.width / 2 - 124,
                        90,
                        20,
                        20,
                        VersionedText.literal("AM"),
                        button -> {
                            AudioManager.playMusic();
                            new ScreenManager().menu();
                        }
                )
        );
    }
}
