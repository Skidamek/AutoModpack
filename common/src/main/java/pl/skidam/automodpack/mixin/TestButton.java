package pl.skidam.automodpack.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.client.ScreenTools;

@Mixin(TitleScreen.class)
public class TestButton extends Screen {

    protected TestButton(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void init(CallbackInfo ci) {

        // check if we are in dev environment
        if (!Platform.isDevelopmentEnvironment()) {
            return;
        }

        this.addDrawableChild(
                new TexturedButtonWidget(
                        this.width / 2 - 124,
                        90,
                        20,
                        20,
                        0,
                        106,
                        20,
                        ButtonWidget.WIDGETS_TEXTURE,
                        256,
                        256,
                        button -> ScreenTools.setTo.menu(),
                        Text.translatable("gui.automodpack.menu")
                )
        );


    }
}
