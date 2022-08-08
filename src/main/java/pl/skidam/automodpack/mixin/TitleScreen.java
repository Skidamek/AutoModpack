package pl.skidam.automodpack.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.client.AutoModpackToast;
import pl.skidam.automodpack.client.StartAndCheck;
import pl.skidam.automodpack.client.modpack.CheckModpack;
import pl.skidam.automodpack.client.ui.ConfirmScreen;
import pl.skidam.automodpack.config.Config;

import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.client.StartAndCheck.isChecking;

@Mixin(net.minecraft.client.gui.screen.TitleScreen.class)
public class TitleScreen extends Screen {

    public TitleScreen(Text title) {
        super(title);
    }

    @Inject(at = @At("RETURN"), method = "initWidgetsNormal" )
    private void AutoModpackUpdateButton(int y, int spacingY, CallbackInfo ci) {
        int Y_CHECK_UPDATES_BUTTON = 0;
        int Y_DELETE_MODPACK_BUTTON = 0;
        if (AutoModpackMain.isModMenu) {
            Y_CHECK_UPDATES_BUTTON = -24;
        }
        if (!AutoModpackMain.isModMenu) {
            Y_DELETE_MODPACK_BUTTON = 24;
        }

        if (Config.CHECK_UPDATES_BUTTON) {
            this.addDrawableChild(new ButtonWidget(this.width / 2 + 104, y + Y_CHECK_UPDATES_BUTTON, 115, 20, Text.translatable("gui.automodpack.button.update"), (button) -> {
                AutoModpackToast.add(0);
                if (!isChecking) {
                    CheckModpack.isCheckUpdatesButtonClicked = true;
                    new StartAndCheck(false, false);
                }
            }));
        }

        if (Config.DELETE_MODPACK_BUTTON && out.exists()) { // out == modpackdir
            this.addDrawableChild(new ButtonWidget(this.width / 2 + 104, y + Y_DELETE_MODPACK_BUTTON, 115, 20, Text.translatable("gui.automodpack.button.delete"), (button) -> Objects.requireNonNull(this.client).setScreen(new ConfirmScreen())));
        }
    }
}