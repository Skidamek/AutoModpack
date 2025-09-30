package pl.skidam.automodpack.client.ui;

import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.utils.SpeedMeter;

import static pl.skidam.automodpack_core.GlobalVariables.clientConfig;
import static pl.skidam.automodpack_core.GlobalVariables.clientConfigFile;

public class DownloadScreen extends VersionedScreen {

    private static final ResourceLocation PROGRESS_BAR_EMPTY_TEXTURE = Common.id("gui/progress-bar-empty.png");
    private static final ResourceLocation PROGRESS_BAR_FULL_TEXTURE = Common.id("gui/progress-bar-full.png");
    private static final int PROGRESS_BAR_WIDTH = 250;
    private static final int PROGRESS_BAR_HEIGHT = 20;
    private final DownloadManager downloadManager;
    private final String header;
    private static long ticks = 0;
    private Button cancelButton;
    private Button muteMusicButton;

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

        this.addRenderableWidget(cancelButton);
        this.addRenderableWidget(muteMusicButton);

        Util.backgroundExecutor().execute(() -> {
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
                    AudioManager.stopMusic();
                }
        );

        // TODO use icon instead of text
        muteMusicButton = VersionedScreen.buttonWidget(
                this.width - 40,
                this.height - 40,
                30,
                20,
                VersionedText.literal("Music"),
                button -> {
                    if (AudioManager.isMusicPlaying()) {
                        AudioManager.stopMusic();
                        clientConfig.downloadMusic = false;
                    } else {
                        AudioManager.playMusic();
                        clientConfig.downloadMusic = true;
                    }
                    ConfigTools.save(clientConfigFile, clientConfig);
                }
        );

        muteMusicButton.visible = false;
    }

    private Component getStage() {
        if (lastStage.equals("-1")) {
            return VersionedText.translatable("automodpack.download.calculating");
        }
        return VersionedText.literal(lastStage);
    }

    private Component getPercentage() {
        if (lastPercentage == -1) {
            return VersionedText.translatable("automodpack.download.calculating");
        }
        return VersionedText.literal(lastPercentage + "%");
    }


    private Component getTotalDownloadSpeed() {
        if (lastSpeed.equals("-1")) {
            return VersionedText.translatable("automodpack.download.calculating");
        }
        return VersionedText.literal(lastSpeed);
    }

    private Component getTotalETA() {
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

        matrices.pushPose();
        matrices.scale(scale, scale, scale);

        if (downloadManager != null && !downloadManager.downloadsInProgress.isEmpty()) {
            drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.download.downloading").withStyle(ChatFormatting.BOLD), this.width / 2, y, TextColors.WHITE);

            // Use a separate variable for the current y position
            int currentY = y + 15;
            synchronized (downloadManager.downloadsInProgress) {
                for (DownloadManager.DownloadData downloadData : downloadManager.downloadsInProgress.values()) {
                    String text = downloadData.getFileName();
                    drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(text), (int) ((float) this.width / 2 * scale), currentY, TextColors.GRAY);
                    currentY += 10;
                }
            }
        } else {
            drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.download.noFiles"), (int) ((float) this.width / 2 * scale), y, TextColors.WHITE);
            drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.wait").withStyle(ChatFormatting.BOLD), (int) ((float) this.width / 2 * scale), y + 25, TextColors.WHITE);
        }

        matrices.popPose();
    }

    private void checkAndStartMusic() {
        if (ticks <= 30) {
            ticks++;
            return;
        }

        if (muteMusicButton.visible) {
            return;
        }

        if (clientConfig.downloadMusic) {
            AudioManager.playMusic();
        }

        muteMusicButton.visible = true;
    }


    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawDownloadingFiles(matrices);
        MutableComponent titleText = VersionedText.literal(header).withStyle(ChatFormatting.BOLD);
        drawCenteredTextWithShadow(matrices, this.font, titleText, this.width / 2, this.height / 2 - 110, TextColors.WHITE);

        if (downloadManager != null && downloadManager.isRunning()) {
            MutableComponent percentage = (MutableComponent) this.getPercentage();
            MutableComponent stage = (MutableComponent) this.getStage();
            MutableComponent eta = (MutableComponent) this.getTotalETA();
            MutableComponent speed = (MutableComponent) this.getTotalDownloadSpeed();
            drawCenteredTextWithShadow(matrices, this.font, stage, this.width / 2, this.height / 2 - 10, TextColors.WHITE);
            drawCenteredTextWithShadow(matrices, this.font, eta, this.width / 2, this.height / 2 + 10, TextColors.WHITE);


            // Render progress bar
            int progressX = this.width / 2 - PROGRESS_BAR_WIDTH / 2;
            int progressY = this.height / 2 + 30;

            drawTexture(PROGRESS_BAR_EMPTY_TEXTURE, matrices, progressX, progressY, 0, 0, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
            drawTexture(PROGRESS_BAR_FULL_TEXTURE, matrices, progressX, progressY, 0, 0, (int) (PROGRESS_BAR_WIDTH * getDownloadScale()), PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);

            drawCenteredTextWithShadow(matrices, this.font, percentage, this.width / 2, this.height / 2 + 36, TextColors.WHITE);
            drawCenteredTextWithShadow(matrices, this.font, speed, this.width / 2, this.height / 2 + 60, TextColors.WHITE);

            checkAndStartMusic();
            cancelButton.active = true;
        } else {
            cancelButton.active = false;
        }

        if (AudioManager.isMusicPlaying()) {
            muteMusicButton.setMessage(VersionedText.literal("Mute"));
        } else {
            muteMusicButton.setMessage(VersionedText.literal("Unmute"));
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
