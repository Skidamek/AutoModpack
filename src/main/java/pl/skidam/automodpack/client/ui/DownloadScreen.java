package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.utils.SpeedMeter;

public class DownloadScreen extends VersionedScreen {

    private static final Identifier PROGRESS_BAR_EMPTY_TEXTURE = Common.id("gui/progress-bar-empty.png");
    private static final Identifier PROGRESS_BAR_FULL_TEXTURE = Common.id("gui/progress-bar-full.png");
    private static final int PROGRESS_BAR_WIDTH = 250;
    private static final int PROGRESS_BAR_HEIGHT = 20;
    private final DownloadManager downloadManager;
    private final String header;
    private static long ticks = 0;
    private ButtonWidget cancelButton;

    private String lastStage = "-1";
    private int lastPercentage = -1;
    private String lastSpeed = "-1";
    private String lastETA = "-1";

    public DownloadScreen(DownloadManager downloadManager, String header) {
        super(VersionedText.literal("DownloadScreen"));
        this.downloadManager = downloadManager;
        this.header = header;
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(cancelButton);

        Util.getMainWorkerExecutor().execute(() -> {
            while (downloadManager != null && downloadManager.isRunning()) {
                lastStage = downloadManager.getStage();
                lastPercentage = downloadManager.getTotalPercentageOfFileSizeDownloaded();
                lastSpeed = SpeedMeter.formatDownloadSpeedToMbps(downloadManager.getSpeedMeter().getAverageSpeedOfLastFewSeconds(1));
                lastETA = SpeedMeter.formatETAToSeconds(downloadManager.getSpeedMeter().getETAInSeconds());
            }
        });
    }

    private void initWidgets() {
        cancelButton = buttonWidget(this.width / 2 - 60, this.height / 2 + 80, 120, 20, VersionedText.translatable("automodpack.cancel"),
                button -> {
                    cancelButton.active = false;
                    cancelDownload();
                }
        );
    }

    private Text getStage() {
        if (lastStage.equals("-1")) {
            return VersionedText.translatable("automodpack.download.calculating");
        }
        return VersionedText.literal(lastStage);
    }

    private Text getPercentage() {
        if (lastPercentage == -1) {
            return VersionedText.translatable("automodpack.download.calculating");
        }
        return VersionedText.literal(lastPercentage + "%");
    }


    private Text getTotalDownloadSpeed() {
        if (lastSpeed.equals("-1")) {
            return VersionedText.translatable("automodpack.download.calculating");
        }
        return VersionedText.literal(lastSpeed);
    }

    private Text getTotalETA() {
        if (lastETA.equals("-1")) {
            return VersionedText.translatable("automodpack.download.calculating");
        }
        return VersionedText.translatable("automodpack.download.eta", lastETA); // Time left: %s
    }

    private float getDownloadScale() {
        return Math.max(0, Math.min(100, lastPercentage)) * 0.01F; // Convert the clamped percentage to a scale between 0.0f and 1.0f
    }

    private void drawDownloadingFiles(VersionedMatrices matrices) {
        float scale = 1.0F;
        int y = this.height / 2 - 90;

        matrices.push();
        matrices.scale(scale, scale, scale);

        if (downloadManager != null && !downloadManager.downloadsInProgress.isEmpty()) {
            drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.download.downloading").formatted(Formatting.BOLD), this.width / 2, y, TextColors.WHITE);

            // Use a separate variable for the current y position
            int currentY = y + 15;
            synchronized (downloadManager.downloadsInProgress) {
                for (DownloadManager.DownloadData downloadData : downloadManager.downloadsInProgress.values()) {
                    String text = downloadData.getFileName();
                    drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.literal(text), (int) ((float) this.width / 2 * scale), currentY, TextColors.GRAY);
                    currentY += 10;
                }
            }
        } else {
            drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.download.noFiles"), (int) ((float) this.width / 2 * scale), y, TextColors.WHITE);
            drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.wait").formatted(Formatting.BOLD), (int) ((float) this.width / 2 * scale), y + 25, TextColors.WHITE);
        }

        matrices.pop();
    }

    private void checkAndStartMusic() {
        if (ticks <= 30) {
            ticks++;
            return;
        }

        if (AudioManager.isMusicPlaying()) {
            return;
        }

        AudioManager.playMusic();
    }


    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawDownloadingFiles(matrices);
        MutableText titleText = VersionedText.literal(header).formatted(Formatting.BOLD);
        drawCenteredTextWithShadow(matrices, this.textRenderer, titleText, this.width / 2, this.height / 2 - 110, TextColors.WHITE);

        if (downloadManager != null && downloadManager.isRunning()) {
            MutableText percentage = (MutableText) this.getPercentage();
            MutableText stage = (MutableText) this.getStage();
            MutableText eta = (MutableText) this.getTotalETA();
            MutableText speed = (MutableText) this.getTotalDownloadSpeed();
            drawCenteredTextWithShadow(matrices, this.textRenderer, stage, this.width / 2, this.height / 2 - 10, TextColors.WHITE);
            drawCenteredTextWithShadow(matrices, this.textRenderer, eta, this.width / 2, this.height / 2 + 10, TextColors.WHITE);


            // Render progress bar
            int progressX = this.width / 2 - PROGRESS_BAR_WIDTH / 2;
            int progressY = this.height / 2 + 30;

            drawTexture(PROGRESS_BAR_EMPTY_TEXTURE, matrices, progressX, progressY, 0, 0, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
            drawTexture(PROGRESS_BAR_FULL_TEXTURE, matrices, progressX, progressY, 0, 0, (int) (PROGRESS_BAR_WIDTH * getDownloadScale()), PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);

            drawCenteredTextWithShadow(matrices, this.textRenderer, percentage, this.width / 2, this.height / 2 + 36, 16777215);
            drawCenteredTextWithShadow(matrices, this.textRenderer, speed, this.width / 2, this.height / 2 + 60, 16777215);

            checkAndStartMusic();
            cancelButton.active = true;
        } else {
            cancelButton.active = false;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    public void cancelDownload() {
        try {
            if (downloadManager != null) {
                downloadManager.cancelAllAndShutdown();
            }

            new ScreenManager().title();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
