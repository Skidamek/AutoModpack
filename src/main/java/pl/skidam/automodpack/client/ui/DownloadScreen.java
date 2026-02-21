package pl.skidam.automodpack.client.ui;

import static pl.skidam.automodpack_core.Constants.clientConfig;
import static pl.skidam.automodpack_core.Constants.clientConfigFile;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.SpeedFormatter;

public class DownloadScreen extends VersionedScreen {

    private static final Identifier PROGRESS_BAR_EMPTY_TEXTURE = Common.id("textures/gui/sprites/green_background.png");
    private static final Identifier PROGRESS_BAR_FULL_TEXTURE = Common.id("textures/gui/sprites/green_progress.png");
    private static final int PROGRESS_BAR_WIDTH = 182;
    private static final int PROGRESS_BAR_HEIGHT = 5;

    private final DownloadManager downloadManager;
    private final String header;

    private long ticks = 0;
    private boolean musicStarted = false;
    private Button cancelButton;
    private Button muteMusicButton;
    private Button playMusicButton;

    // UI Cache
    private String cachedStage = "0/0";
    private double cachedPercentage = 0.0;
    private String cachedSpeed = "0 B/s";
    private String cachedETA = "Calculating...";

    private long lastTextUpdate = 0;
    private static final long TEXT_UPDATE_INTERVAL = 100;

    public DownloadScreen(DownloadManager downloadManager, String header) {
        super(VersionedText.literal("DownloadScreen"));
        this.downloadManager = downloadManager;
        this.header = header;
    }

    @Override
    protected void init() {
        super.init();
        initWidgets();
    }

    private void initWidgets() {
        cancelButton = addRenderableWidget(
                buttonWidget(this.width / 2 - 60, this.height / 2 + 80, 120, 20,
                        VersionedText.translatable("automodpack.cancel"),
                        button -> {
                            cancelButton.active = false;
                            cancelDownload();
                            AudioManager.stopMusic();
                        }
                )
        );

        int x = this.width - 40;
        int y = this.height - 40;

        muteMusicButton = addRenderableWidget(
                VersionedScreen.iconButtonWidget(x, y, 20, 8,
                        button -> {
                            AudioManager.stopMusic();
                            clientConfig.playMusic = false;
                            ConfigTools.save(clientConfigFile, clientConfig);
                        }, "music-note"
                )
        );

        playMusicButton = addRenderableWidget(
                VersionedScreen.iconButtonWidget(x, y, 20, 8,
                        button -> {
                            AudioManager.playMusic();
                            clientConfig.playMusic = true;
                            ConfigTools.save(clientConfigFile, clientConfig);
                        }, "mute-music-note"
                )
        );
    }

    private void updateUIState() {
        if (downloadManager == null || !downloadManager.isRunning()) return;

        long now = System.currentTimeMillis();
        if (now - lastTextUpdate >= TEXT_UPDATE_INTERVAL) {
            lastTextUpdate = now;

            cachedStage = downloadManager.getStage();
            cachedPercentage = downloadManager.getPrecisePercentage();
            cachedSpeed = SpeedFormatter.formatSpeed(downloadManager.getDownloadSpeed());
            cachedETA = SpeedFormatter.formatETA(downloadManager.getETA());
        }
    }

    // --- Components ---

    private Component getStage() { return VersionedText.literal(cachedStage); }
    private Component getPercentage() { return VersionedText.literal((int) cachedPercentage + "%"); }
    private Component getTotalDownloadSpeed() {
        return "-1".equals(cachedSpeed)
                ? VersionedText.translatable("automodpack.download.calculating")
                : VersionedText.literal(cachedSpeed);
    }
    private Component getTotalETA() {
        return "-1".equals(cachedETA)
                ? VersionedText.translatable("automodpack.download.calculating")
                : VersionedText.translatable("automodpack.download.eta", cachedETA);
    }

    private float getDownloadScale() {
        return (float) (Math.max(0.0, Math.min(100.0, cachedPercentage)) * 0.01);
    }

    private void drawDownloadingFiles(VersionedMatrices matrices) {
        float scale = 1.0F;
        int y = this.height / 2 - 90;

        matrices.pushPose();
        matrices.scale(scale, scale, scale);

        if (downloadManager != null && !downloadManager.downloadsInProgress.isEmpty()) {
            drawCenteredText(matrices, this.font,
                    VersionedText.translatable("automodpack.download.downloading").withStyle(ChatFormatting.BOLD),
                    this.width / 2, y, TextColors.WHITE);

            int currentY = y + 15;
            synchronized (downloadManager.downloadsInProgress) {
                for (DownloadManager.DownloadData data : downloadManager.downloadsInProgress.values()) {
                    drawCenteredText(matrices, this.font,
                            VersionedText.literal(data.getFileName()),
                            (int) (((float) this.width / 2) * scale), currentY, TextColors.GRAY);
                    currentY += 10;
                }
            }
        } else {
            drawCenteredText(matrices, this.font,
                    VersionedText.translatable("automodpack.download.noFiles"),
                    (int) (((float) this.width / 2) * scale), y, TextColors.WHITE);
            drawCenteredText(matrices, this.font,
                    VersionedText.translatable("automodpack.wait").withStyle(ChatFormatting.BOLD),
                    (int) (((float) this.width / 2) * scale), y + 24, TextColors.WHITE);
        }
        matrices.popPose();
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        updateUIState();
        int lineHeight = 12;

        drawDownloadingFiles(matrices);

        // Title
        drawCenteredText(matrices, this.font,
                VersionedText.literal(header).withStyle(ChatFormatting.BOLD),
                this.width / 2, this.height / 2 - 110, TextColors.WHITE);

        if (downloadManager != null && downloadManager.isRunning()) {
            drawCenteredText(matrices, this.font, (MutableComponent) getStage(), this.width / 2, this.height / 2 - 10, TextColors.WHITE);
            drawCenteredText(matrices, this.font, (MutableComponent) getTotalETA(), this.width / 2, this.height / 2 - 10 + lineHeight * 2, TextColors.WHITE);

            float scaleBar = 1.35F;
            int barWidth = PROGRESS_BAR_WIDTH;
            int barHeight = PROGRESS_BAR_HEIGHT;
            int barFilledWidth = (int) (barWidth * getDownloadScale());
            int barYPos = this.height / 2 + 36;

            float barDrawX = (this.width - barWidth * scaleBar) / 2.0F / scaleBar;
            float barDrawY = barYPos / scaleBar;

            matrices.pushPose();
            matrices.scale(scaleBar, scaleBar, scaleBar);
            drawTexture(PROGRESS_BAR_EMPTY_TEXTURE, matrices, Math.round(barDrawX), Math.round(barDrawY), 0, 0, barWidth, barHeight, barWidth, barHeight);
            drawTexture(PROGRESS_BAR_FULL_TEXTURE, matrices, Math.round(barDrawX), Math.round(barDrawY), 0, 0, Math.min(barFilledWidth, barWidth), barHeight, barWidth, barHeight);
            matrices.popPose();

            drawCenteredText(matrices, this.font, (MutableComponent) getTotalDownloadSpeed(), this.width / 2, this.height / 2 + 36 + lineHeight * 2, TextColors.WHITE);
            cancelButton.active = true;
        } else {
            cancelButton.active = false;
        }

        checkAndStartMusic();
        updateMusicButtons();
    }

    private void updateMusicButtons() {
        if (playMusicButton.active && muteMusicButton.active) {
            boolean playing = AudioManager.isMusicPlaying();
            muteMusicButton.visible = playing;
            playMusicButton.visible = !playing;
        } else {
            muteMusicButton.visible = clientConfig.playMusic;
            playMusicButton.visible = !clientConfig.playMusic;
        }
    }

    private void checkAndStartMusic() {
        if (ticks++ <= 30) {
            muteMusicButton.active = false;
            playMusicButton.active = false;
            return;
        }
        muteMusicButton.active = true;
        playMusicButton.active = true;

        if (musicStarted) return;
        if (clientConfig.playMusic) AudioManager.playMusic();
        musicStarted = true;
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    public void cancelDownload() {
        try {
            if (downloadManager != null) downloadManager.cancelAllAndShutdown();
            new ScreenManager().title();
        } catch (Exception e) { e.printStackTrace(); }
    }
}