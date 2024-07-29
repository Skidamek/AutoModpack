package pl.skidam.automodpack.mixin.dev;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

@Mixin(TitleScreen.class)
public class TestButton extends Screen {

    protected TestButton(Text title) {
        super(title);
    }

    @Inject(
            method = "init",
            at = @At("HEAD")
    )
    private void init(CallbackInfo ci) {

        // check if we are in dev environment
        if (!new LoaderManager().isDevelopmentEnvironment()) {
            return;
        }

/*? if >=1.17 {*/
        this.addDrawableChild(
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
