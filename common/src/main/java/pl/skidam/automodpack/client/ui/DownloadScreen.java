package pl.skidam.automodpack.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.utils.DownloadInfo;

import java.util.ArrayList;
import java.util.List;

import static pl.skidam.automodpack.StaticVariables.MOD_ID;
import static pl.skidam.automodpack.client.ModpackUpdater.downloadInfos;
import static pl.skidam.automodpack.client.ModpackUpdater.getDownloadInfo;
import static pl.skidam.automodpack.utils.RefactorStrings.getETA;

@Environment(EnvType.CLIENT)
public class DownloadScreen extends Screen {

    private static final Identifier PROGRESS_BAR_EMPTY_TEXTURE = new Identifier(MOD_ID, "gui/progress-bar-empty.png");
    private static final Identifier PROGRESS_BAR_FULL_TEXTURE = new Identifier(MOD_ID, "gui/progress-bar-full.png");
    private static final int PROGRESS_BAR_WIDTH = 250;
    private static final int PROGRESS_BAR_HEIGHT = 20;
    private static int ticks = 0;

    public DownloadScreen() {
        super(TextHelper.literal("DownloadScreen"));
    }

    @Override
    protected void init() {
        super.init();
        this.addDrawableChild(ButtonWidget.builder(TextHelper.translatable("automodpack.cancel"), button -> ModpackUpdater.cancelDownload()).position(this.width / 2 - 50, this.height - 25).size(100, 20).build());
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

        long boostedFileSize = fileSize / 512;

        while (boostedFileSize >= 1024) {
            boostedFileSize /= 1024;
            unitIndex++;
        }

        long roundedFileSize = Math.round(fileSize);
        long roundedBytesDownloaded = Math.round(bytesDownloaded);
        if (roundedBytesDownloaded > roundedFileSize) {
            roundedBytesDownloaded = roundedFileSize;
        }

        return roundedBytesDownloaded + "/" + roundedFileSize + " " + units[unitIndex];
    }

    private Text getTotalDownloadSpeed() {
        double speed = ModpackUpdater.getTotalDownloadSpeed();
        if (speed > 0) {
            int roundedSpeed = (int) Math.round(speed);
            return TextHelper.literal(roundedSpeed + " MB/s");
        }

        return TextHelper.translatable("automodpack.download.calculating"); // Calculating...
    }

    private Text getTotalETA() {
        String eta = ModpackUpdater.getTotalETA();
        return TextHelper.translatable("automodpack.download.eta", eta); // Time left: %s
    }

    private String getETAOfFile(String file) {
        if (getDownloadInfo(file) == null) return "0s";
        double eta = getDownloadInfo(file).getEta();

        if (getDownloadInfo(file).getEta() <= 0) return "N/A";

        return getETA(eta);
    }

    private void drawDownloadingFiles(DrawContext context) {
        float scale = 1.0F;
        int y = this.height / 2 - 90;

        context.getMatrices().push();
        context.getMatrices().scale(scale, scale, scale);

        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);

        if (downloadInfosCopy.size() > 0) {
            context.drawCenteredTextWithShadow(this.textRenderer, TextHelper.translatable("automodpack.download.downloading"), (int) (this.width / 2 * scale), y, 16777215);

            // Use a separate variable for the current y position
            int currentY = y + 15;
            for (DownloadInfo file : downloadInfosCopy) {
                if (file == null) continue;
                String fileName = file.getFileName();
                context.drawCenteredTextWithShadow(this.textRenderer, fileName + " (" + getDownloadedSize(fileName) + ")" + " - " + getETAOfFile(fileName), (int) (this.width / 2 * scale), currentY, 16777215);
                currentY += 10;
            }
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer, TextHelper.translatable("automodpack.download.noFiles"), (int) (this.width / 2 * scale), y, 16777215);

            // Please wait...
            context.drawCenteredTextWithShadow(this.textRenderer, TextHelper.translatable("automodpack.wait").formatted(Formatting.BOLD), (int) (this.width / 2 * scale), y + 25, 16777215);
        }

        context.getMatrices().pop();
    }

    private void checkAndStartMusic() {
        if (ticks <= 60) {
            ticks++;
            return;
        }

        String eta = ModpackUpdater.getTotalETA();
        try {
            int etaInSeconds = Integer.parseInt(eta.substring(0, eta.length() - 1));
            if (etaInSeconds > 3) {
                AudioManager.playMusic();
            }
        } catch (NumberFormatException ignored) {
        }
    }


    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        Text percentage = this.getPercentage();
        Text stage = this.getStage();
        Text eta = this.getTotalETA();
        Text speed = this.getTotalDownloadSpeed();
        context.drawCenteredTextWithShadow(this.textRenderer, stage, this.width / 2, this.height / 2 - 10, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, eta, this.width / 2, this.height / 2 + 10, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, percentage, this.width / 2, this.height / 2 + 30, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, speed, this.width / 2, this.height / 2 + 80, 16777215);

        drawDownloadingFiles(context);

        Text modpackName = TextHelper.literal(ModpackUpdater.getModpackName()).formatted(Formatting.BOLD);
        context.drawCenteredTextWithShadow(this.textRenderer, modpackName, this.width / 2, this.height / 2 - 110, 16777215);

        // Render progress bar
        int x = this.width / 2 - PROGRESS_BAR_WIDTH / 2;
        int y = this.height / 2 + 50;

        context.drawTexture(PROGRESS_BAR_EMPTY_TEXTURE, x, y, 0, 0, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
        context.drawTexture(PROGRESS_BAR_FULL_TEXTURE, x, y, 0, 0, (int)(PROGRESS_BAR_WIDTH * ((float) ModpackUpdater.getTotalPercentageOfFileSizeDownloaded() / 100)), PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);

        checkAndStartMusic();

        super.render(context, mouseX, mouseY, delta);
    }

    public boolean shouldCloseOnEsc() {
        return false;
    }
}
