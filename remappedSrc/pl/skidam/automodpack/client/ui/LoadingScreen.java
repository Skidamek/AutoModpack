package pl.skidam.automodpack.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.UnZipper;

@Environment(EnvType.CLIENT)
public class LoadingScreen extends Screen {

    public LoadingScreen() {
        super(Text.translatable("gui.automodpack.screen.loading.title").formatted(Formatting.BOLD));
        // It needs to be here to restart unzip progress value
        UnZipper.progress = 0;
    }

    private Text getProgress() {
        Text percentage = Text.translatable(Download.progress + "%");
        if (Download.progress == 100) {
            percentage = Text.translatable(UnZipper.progress + "%");
            if (UnZipper.progress == 100) {
                percentage = Text.translatable("gui.automodpack.screen.loading.wait"); // Please wait...
            }
        }
        return percentage;
    }

    private Text getStep() {
        Text step = Text.translatable("gui.automodpack.screen.loading.download"); // Downloading...
        if (Download.progress == 100) {
            step = Text.translatable("gui.automodpack.screen.loading.extract"); // Extracting modpack...
        }
        if (UnZipper.progress == 100) {
            step = Text.translatable("gui.automodpack.screen.loading.finish"); // Finishing...
        }
        return step;
    }

    private String getInternetConnectionSpeed() {
        if (Download.progress > 0 && Download.progress < 100) {
            return Download.averageInternetConnectionSpeed;
        } else {
            return "";
        }
    }

    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        Text percentage = this.getProgress();
        Text step = this.getStep();
        String internetConnectionSpeed = this.getInternetConnectionSpeed();
        drawCenteredText(matrices, this.textRenderer, step, this.width / 2, 80, 16777215);
        drawCenteredText(matrices, this.textRenderer, percentage, this.width / 2, 100, 16777215);
        drawCenteredText(matrices, this.textRenderer, internetConnectionSpeed, this.width / 2, 120, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean shouldCloseOnEsc() { return false; }

}
