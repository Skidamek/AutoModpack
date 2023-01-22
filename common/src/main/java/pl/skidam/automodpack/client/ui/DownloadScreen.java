package pl.skidam.automodpack.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.utils.DownloadInfo;
import pl.skidam.automodpack.client.ModpackUpdater;

import java.util.ArrayList;
import java.util.List;

import static pl.skidam.automodpack.client.ModpackUpdater.downloadInfos;
import static pl.skidam.automodpack.client.ModpackUpdater.getDownloadInfo;

public class DownloadScreen extends Screen {

    private static final Identifier PROGRESS_BAR_EMPTY_TEXTURE = new Identifier(AutoModpack.MOD_ID, "gui/progress-bar-empty.png");
    private static final Identifier PROGRESS_BAR_FULL_TEXTURE = new Identifier(AutoModpack.MOD_ID, "gui/progress-bar-full.png");
    private static final int PROGRESS_BAR_WIDTH = 250;
    private static final int PROGRESS_BAR_HEIGHT = 20;

    public DownloadScreen() {
        super(TextHelper.literal("DownloadScreen"));
    }

    @Override
    protected void init() {
        super.init();
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 50, this.height - 25, 100, 20, TextHelper.translatable("gui.automodpack.screen.download.button.cancel"), (button) -> ModpackUpdater.cancelDownload()));
    }

    private Text getStage() {
        String stage = ModpackUpdater.getStage();
        return TextHelper.literal(stage);
    }

    private Text getPercentage() {
        int percentage = ModpackUpdater.getTotalPercentageOfFileSizeDownloaded();
        return TextHelper.literal(percentage + "%");
    }

    private String getDownloadedSize(String file) {
        DownloadInfo downloadInfo = getDownloadInfo(file);
        if (downloadInfo == null) return "N/A";
        long fileSize = downloadInfo.getFileSize();
        double bytesDownloaded = downloadInfo.getBytesDownloaded();

        if (fileSize == -1 || bytesDownloaded == -1) return "N/A";

        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;

        while (fileSize >= 1024) {
            fileSize /= 1024;
            unitIndex++;
        }

        bytesDownloaded /= Math.pow(1024, unitIndex);
        bytesDownloaded = Math.ceil(bytesDownloaded);
        return (long) bytesDownloaded + "/" + fileSize + units[unitIndex];
    }

    private Text getTotalDownloadSpeed() {
        double speed = ModpackUpdater.getTotalDownloadSpeed();
        if (speed > 0) {
            return TextHelper.literal(speed + " MB/s");
        }

        return TextHelper.translatable("gui.automodpack.screen.download.text.calculating"); // Calculating...
    }

    private Text getTotalETA() {
        String eta = ModpackUpdater.getTotalETA();
        return TextHelper.literal("ETA: " + eta);
    }

    private String getETAOfFile(String file) {
        if (getDownloadInfo(file) == null) return "0s";
        double eta = getDownloadInfo(file).getEta();

        if (getDownloadInfo(file).getEta() <= 0) return "N/A";

        int hours = (int) (eta / 3600);
        int minutes = (int) ((eta % 3600) / 60);
        int seconds = (int) (eta % 60);

        if (hours > 0) {
            return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private void drawDownloadingFiles(MatrixStack matrices) {
        float scale = 1.0F;
        int y = this.height / 2 - 90;

        matrices.push();
        matrices.scale(scale, scale, scale);

        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);

        if (downloadInfosCopy.size() > 0) {
            drawCenteredText(matrices, this.textRenderer, TextHelper.translatable("gui.automodpack.screen.download.text.downloading"), (int) (this.width / 2 * scale), y, 16777215);

            // Use a separate variable for the current y position
            int currentY = y + 15;
            for (DownloadInfo file : downloadInfosCopy) {
                if (file == null) continue;
                String fileName = file.getFileName();
                drawCenteredText(matrices, this.textRenderer, fileName + " (" + getDownloadedSize(fileName) + ")" + " - " + getETAOfFile(fileName), (int) (this.width / 2 * scale), currentY, 16777215);
                currentY += 10;
            }
        } else {
            drawCenteredText(matrices, this.textRenderer, TextHelper.translatable("gui.automodpack.screen.download.text.no_files"), (int) (this.width / 2 * scale), y, 16777215);
        }

        matrices.pop();
    }


    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        Text percentage = this.getPercentage();
        Text stage = this.getStage();
        Text eta = this.getTotalETA();
        Text speed = this.getTotalDownloadSpeed();
        drawCenteredText(matrices, this.textRenderer, stage, this.width / 2, this.height / 2 - 10, 16777215);
        drawCenteredText(matrices, this.textRenderer, eta, this.width / 2, this.height / 2 + 10, 16777215);
        drawCenteredText(matrices, this.textRenderer, percentage, this.width / 2, this.height / 2 + 30, 16777215);
        drawCenteredText(matrices, this.textRenderer, speed, this.width / 2, this.height / 2 + 80, 16777215);

        drawDownloadingFiles(matrices);

        // Render progress bar
        int x = this.width / 2 - PROGRESS_BAR_WIDTH / 2;
        int y = this.height / 2 + 50;

        RenderSystem.setShaderTexture(0, PROGRESS_BAR_EMPTY_TEXTURE);
        drawTexture(matrices, x, y, 0, 0, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
        RenderSystem.setShaderTexture(0, PROGRESS_BAR_FULL_TEXTURE);
        drawTexture(matrices, x, y, 0, 0, (int)(PROGRESS_BAR_WIDTH * ((float) ModpackUpdater.getTotalPercentageOfFileSizeDownloaded() / 100)), PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);

        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean shouldCloseOnEsc() {
        return false;
    }
}
