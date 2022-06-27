package pl.skidam.automodpack.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.ShityDeCompressor;

@Environment(EnvType.CLIENT)
public class LoadingScreen extends Screen {

    public LoadingScreen() {
        super(Text.translatable("gui.automodpack.screen.loading.title").formatted(Formatting.BOLD));
        // it needs to be here to restart unzip progress value
        ShityDeCompressor.progress = 0;
    }

    private String getPercentage() {
        int percentage = Download.downloadPercent;
        if (percentage == 100) {
            percentage = ShityDeCompressor.progress;
            if (percentage == 100) {
                return "Please wait...";
            }
        }
        return MathHelper.clamp(percentage, 0, 100) + "%";
    }

    private String getStep() {
        String step = "Downloading modpack...";
        if (Download.downloadPercent == 100) {
            step = "Extracting modpack...";
        }
        if (ShityDeCompressor.progress == 100) {
            step = "Finishing...";
        }
        return step;
    }

    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        String percentage = this.getPercentage();
        String step = this.getStep();
        drawCenteredText(matrices, this.textRenderer, step, this.width / 2, 100, 16777215);
        drawCenteredText(matrices, this.textRenderer, percentage, this.width / 2, 300 / 2 - 9 / 2 - 30, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean shouldCloseOnEsc() { return false; }

}
