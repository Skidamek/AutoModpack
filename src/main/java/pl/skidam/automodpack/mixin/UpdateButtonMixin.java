package pl.skidam.automodpack.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.client.AutoModpackToast;
import pl.skidam.automodpack.client.StartAndCheck;
import pl.skidam.automodpack.client.modpack.CheckModpack;
import pl.skidam.automodpack.client.sound.ModSounds;
import pl.skidam.automodpack.client.ui.ConfirmScreen;
import pl.skidam.automodpack.config.Config;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.client.StartAndCheck.isChecking;

@Mixin(TitleScreen.class)
public class UpdateButtonMixin extends Screen {

    public UpdateButtonMixin(Text title) {
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

        assert this.client != null;
        this.client.execute(() -> this.client.getSoundManager().play(
                new PositionedSoundInstance(ModSounds.ELEVATOR_MUSIC.getId(),
                        SoundCategory.MUSIC,
                        10f,
                        1f,
                        true,
                        0,
                        SoundInstance.AttenuationType.NONE,
                        0.0D, 0.0D, 0.0D,
                        true)));


        if (Config.CHECK_UPDATES_BUTTON) {
            this.addDrawableChild(new ButtonWidget(this.width / 2 - 100 + 206, y + Y_CHECK_UPDATES_BUTTON, 115, 20, new TranslatableText("gui.automodpack.button.update"), (button) -> {
                AutoModpackToast.add(0);
                if (!isChecking) {
                    CheckModpack.isCheckUpdatesButtonClicked = true;
                    new StartAndCheck(false, false);
                }
            }));
        }

        if (Config.DELETE_MODPACK_BUTTON && out.exists()) { // out == modpackdir
            this.addDrawableChild(new ButtonWidget(this.width / 2 - 100 + 206, y + Y_DELETE_MODPACK_BUTTON, 115, 20, new TranslatableText("gui.automodpack.button.delete"), (button) -> {
                this.client.setScreen(new ConfirmScreen());
            }));
        }
    }
}