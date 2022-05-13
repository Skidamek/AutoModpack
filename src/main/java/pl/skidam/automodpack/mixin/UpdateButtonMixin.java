package pl.skidam.automodpack.mixin;

import net.minecraft.client.gui.screen.ConfirmChatLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.SelfUpdater;
import pl.skidam.automodpack.ToastExecutor;
import pl.skidam.automodpack.modpack.Modpack;


@Mixin(TitleScreen.class)
public abstract class UpdateButtonMixin extends Screen {

    protected UpdateButtonMixin(Text title) {
        super(title);
    }
    public Text Button = new TranslatableText("gui.automodpack.button.update");

    @Inject(at = @At("RETURN"), method = "initWidgetsNormal" )
    private void AutoModpackUpdateButton(int y, int spacingY, CallbackInfo ci) {
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 100 + 206, y, 115, 20, Button, (button) -> {
            new Thread(new Modpack(0)).start();
            new Thread(new SelfUpdater(0)).start();
            new ToastExecutor(0);
            this.client.setScreen(new ConfirmChatLinkScreen((button1) -> {

                Util.getOperatingSystem().open("https://github.com/Skidamek/AutoModpack");

                this.client.setScreen(this);
            }, "https://github.com/Skidamek/AutoModpack", true));
        }));

    }
}