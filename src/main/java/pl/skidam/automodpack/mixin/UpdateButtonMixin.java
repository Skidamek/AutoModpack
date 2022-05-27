package pl.skidam.automodpack.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.*;
import pl.skidam.automodpack.StartAndCheck;
import pl.skidam.automodpack.utils.ToastExecutor;

@Mixin(TitleScreen.class)
public class UpdateButtonMixin extends Screen {

    public UpdateButtonMixin(Text title) {
        super(title);
    }
    public Text Button = new TranslatableText("gui.automodpack.button.update");

    @Inject(at = @At("RETURN"), method = "initWidgetsNormal" )
    private void AutoModpackUpdateButton(int y, int spacingY, CallbackInfo ci) {
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 100 + 206, y, 115, 20, Button, (button) -> {
            new ToastExecutor(0);
            if (!AutoModpackClient.Checking) {
                new Thread(() -> new StartAndCheck(false)).start();
            }
        }));
    }
}