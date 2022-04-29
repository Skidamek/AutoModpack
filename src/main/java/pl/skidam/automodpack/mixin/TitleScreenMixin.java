package pl.skidam.automodpack.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.Modpack.Modpack;
import pl.skidam.automodpack.SelfUpdater;

@Mixin(TitleScreen.class)

public abstract class TitleScreenMixin extends Screen {


    protected TitleScreenMixin(Text title) {
        super(title);
    }
    public Text Button = Text.of("Check Updates!");

    @Inject(at = @At("RETURN"), method = "initWidgetsNormal" )
    private void AutoModpackUpdateButton(int y, int spacingY, CallbackInfo ci) {
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 100 + 205, y, 50, 20, Button, (button) -> {
            new Thread(new Modpack()).start();
            new Thread(new SelfUpdater()).start();
            Button = Text.of("Checking...");
        }));
    }
}