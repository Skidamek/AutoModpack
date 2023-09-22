/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.DownloadManager;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

import static pl.skidam.automodpack_common.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_common.GlobalVariables.MOD_ID;

public class DownloadScreen extends VersionedScreen {

    private static final Identifier PROGRESS_BAR_EMPTY_TEXTURE = new Identifier(MOD_ID, "gui/progress-bar-empty.png");
    private static final Identifier PROGRESS_BAR_FULL_TEXTURE = new Identifier(MOD_ID, "gui/progress-bar-full.png");
    private static final int PROGRESS_BAR_WIDTH = 250;
    private static final int PROGRESS_BAR_HEIGHT = 20;
    private final DownloadManager downloadManager;
    private final String header;
    private static long ticks = 0;
    private ButtonWidget cancelButton;

    // Temp save for the last download values
//    private final Map<String, String> mapOfFileStats = new HashMap<>(); // URL, Percentage of download
    private String lastStage = "-1";
    private int lastPercentage = -1;
    private String lastSpeed = "-1";
    private String lastETA = "-1";
    private float lastDownloadedScale = 0.0F;

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
            while (downloadManager != null && !downloadManager.isClosed()) {

//                for (Map.Entry<String, DownloadManager.DownloadData> map : ModpackUpdater.downloadManager.downloadsInProgress.entrySet()) {
//                    mapOfFileStats.put(map.getKey(), ModpackUpdater.downloadManager.getPercentageOfFileSizeDownloaded(map.getKey()) + "%");
//                }
//
//                for (Map.Entry<String, String> map : mapOfFileStats.entrySet()) {
//                    if (!ModpackUpdater.downloadManager.downloadsInProgress.containsKey(map.getKey())) {
//                        mapOfFileStats.remove(map.getKey());
//                    }
//                }

                // TODO make it work better pls
                lastStage = downloadManager.getStage();
                lastPercentage = (int) downloadManager.getTotalPercentageOfFileSizeDownloaded();
                lastDownloadedScale = (float) (downloadManager.getTotalPercentageOfFileSizeDownloaded() * 0.01);

                long totalDownloadSpeed = downloadManager.getTotalDownloadSpeed();
                lastSpeed = downloadManager.getTotalDownloadSpeedInReadableFormat(totalDownloadSpeed);
                lastETA = downloadManager.getTotalETA(totalDownloadSpeed);
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
        return lastDownloadedScale;
    }

    private void drawDownloadingFiles(VersionedMatrices matrices) {
        float scale = 1.0F;
        int y = this.height / 2 - 90;

        matrices.push();
        matrices.scale(scale, scale, scale);

        if (downloadManager != null && !downloadManager.downloadsInProgress.isEmpty()) {
            drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.download.downloading"), (int) (this.width / 2 * scale), y, 16777215);

            // Use a separate variable for the current y position
            int currentY = y + 15;
            synchronized (downloadManager.downloadsInProgress) {
                for (DownloadManager.DownloadData downloadData : downloadManager.downloadsInProgress.values()) {

                    String text = downloadData.getFileName();

//                    DownloadManager.DownloadData downloadData = map.getValue();
//                    String percentage = mapOfFileStats.get(map.getKey());
//
//                    if (percentage != null) {
//                        text += " " + percentage;
//                    }

                    drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.literal(text), (int) (this.width / 2 * scale), currentY, 16777215);
                    currentY += 10;
                }
            }
        } else {
            drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.download.noFiles"), (int) (this.width / 2 * scale), y, 16777215);
            drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.wait").formatted(Formatting.BOLD), (int) (this.width / 2 * scale), y + 25, 16777215);
        }

        matrices.pop();
    }

    private void checkAndStartMusic() {
        if (ticks <= 60) {
            ticks++;
            return;
        }

        if (AudioManager.isMusicPlaying()) {
            return;
        }

        try {
            int etaInSeconds = Integer.parseInt(lastETA.substring(0, lastETA.length() - 1));
            if (etaInSeconds > 5) {
                AudioManager.playMusic();
            }
        } catch (NumberFormatException ignored) {
        }
    }


    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices, mouseX, mouseY, delta);

        drawDownloadingFiles(matrices);
        MutableText titleText = VersionedText.literal(header).formatted(Formatting.BOLD);
        drawCenteredTextWithShadow(matrices, this.textRenderer, titleText, this.width / 2, this.height / 2 - 110, 16777215);

        if (downloadManager != null && !downloadManager.isClosed()) {
            MutableText percentage = (MutableText) this.getPercentage();
            MutableText stage = (MutableText) this.getStage();
            MutableText eta = (MutableText) this.getTotalETA();
            MutableText speed = (MutableText) this.getTotalDownloadSpeed();
            drawCenteredTextWithShadow(matrices, this.textRenderer, stage, this.width / 2, this.height / 2 - 10, 16777215);
            drawCenteredTextWithShadow(matrices, this.textRenderer, eta, this.width / 2, this.height / 2 + 10, 16777215);


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

            LOGGER.info("Download canceled");

            // todo delete files that were downloaded
            // we will use the same method as to modpacks manager

            new ScreenManager().title();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
