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
import pl.skidam.automodpack.utils.DownloadManager;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

import static pl.skidam.automodpack.GlobalVariables.MOD_ID;

public class DownloadScreen extends VersionedScreen {

    private static final Identifier PROGRESS_BAR_EMPTY_TEXTURE = new Identifier(MOD_ID, "gui/progress-bar-empty.png");
    private static final Identifier PROGRESS_BAR_FULL_TEXTURE = new Identifier(MOD_ID, "gui/progress-bar-full.png");
    private static final int PROGRESS_BAR_WIDTH = 250;
    private static final int PROGRESS_BAR_HEIGHT = 20;
    private static long ticks = 0;
    private ButtonWidget cancelButton;

    // Temp save for the last download values
//    private final Map<String, String> mapOfFileStats = new HashMap<>(); // URL, Percentage of download
    private String lastStage = "-1";
    private int lastPercentage = -1;
    private String lastSpeed = "-1";
    private String lastETA = "-1";
    private float lastDownloadedScale = 0.0F;

    public DownloadScreen() {
        super(VersionedText.common.literal("DownloadScreen"));
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(cancelButton);

        Util.getMainWorkerExecutor().execute(() -> {
            while (ModpackUpdater.downloadManager != null && !ModpackUpdater.downloadManager.isClosed()) {

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
                lastStage = ModpackUpdater.downloadManager.getStage();
                lastPercentage = (int) ModpackUpdater.downloadManager.getTotalPercentageOfFileSizeDownloaded();
                lastDownloadedScale = (float) (ModpackUpdater.downloadManager.getTotalPercentageOfFileSizeDownloaded() * 0.01);

                long totalDownloadSpeed = ModpackUpdater.downloadManager.getTotalDownloadSpeed();
                lastSpeed = ModpackUpdater.downloadManager.getTotalDownloadSpeedInReadableFormat(totalDownloadSpeed);
                lastETA = ModpackUpdater.downloadManager.getTotalETA(totalDownloadSpeed);
            }
        });
    }

    private void initWidgets() {
        cancelButton = VersionedText.buttonWidget(this.width / 2 - 60, this.height / 2 + 80, 120, 20, VersionedText.common.translatable("automodpack.cancel"),
                button -> {
                    cancelButton.active = false;
                    ModpackUpdater.cancelDownload();
                }
        );
    }

    private Text getStage() {
        if (lastStage.equals("-1")) {
            return VersionedText.common.translatable("automodpack.download.calculating");
        }
        return VersionedText.common.literal(lastStage);
    }

    private Text getPercentage() {
        if (lastPercentage == -1) {
            return VersionedText.common.translatable("automodpack.download.calculating");
        }
        return VersionedText.common.literal(lastPercentage + "%");
    }


    private Text getTotalDownloadSpeed() {
        if (lastSpeed.equals("-1")) {
            return VersionedText.common.translatable("automodpack.download.calculating");
        }
        return VersionedText.common.literal(lastSpeed);
    }

    private Text getTotalETA() {
        if (lastETA.equals("-1")) {
            return VersionedText.common.translatable("automodpack.download.calculating");
        }
        return VersionedText.common.translatable("automodpack.download.eta", lastETA); // Time left: %s
    }

    private float getDownloadScale() {
        return lastDownloadedScale;
    }

    private void drawDownloadingFiles(VersionedMatrices matrices) {
        float scale = 1.0F;
        int y = this.height / 2 - 90;

        matrices.push();
        matrices.scale(scale, scale, scale);

        if (ModpackUpdater.downloadManager != null && ModpackUpdater.downloadManager.downloadsInProgress.size() > 0) {
            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.download.downloading"), (int) (this.width / 2 * scale), y, 16777215);

            // Use a separate variable for the current y position
            int currentY = y + 15;
            synchronized (ModpackUpdater.downloadManager.downloadsInProgress) {
                for (DownloadManager.DownloadData downloadData : ModpackUpdater.downloadManager.downloadsInProgress.values()) {

                    String text = downloadData.getFileName();

//                    DownloadManager.DownloadData downloadData = map.getValue();
//                    String percentage = mapOfFileStats.get(map.getKey());
//
//                    if (percentage != null) {
//                        text += " " + percentage;
//                    }

                    VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.literal(text), (int) (this.width / 2 * scale), currentY, 16777215);
                    currentY += 10;
                }
            }
        } else {
            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.download.noFiles"), (int) (this.width / 2 * scale), y, 16777215);
            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.wait").formatted(Formatting.BOLD), (int) (this.width / 2 * scale), y + 25, 16777215);
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
        this.renderBackground(matrices);

        drawDownloadingFiles(matrices);
        MutableText modpackName = VersionedText.common.literal(ModpackUpdater.getModpackName()).formatted(Formatting.BOLD);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, modpackName, this.width / 2, this.height / 2 - 110, 16777215);

        if (ModpackUpdater.downloadManager != null && !ModpackUpdater.downloadManager.isClosed()) {
            MutableText percentage = (MutableText) this.getPercentage();
            MutableText stage = (MutableText) this.getStage();
            MutableText eta = (MutableText) this.getTotalETA();
            MutableText speed = (MutableText) this.getTotalDownloadSpeed();
            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, stage, this.width / 2, this.height / 2 - 10, 16777215);
            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, eta, this.width / 2, this.height / 2 + 10, 16777215);


            // Render progress bar
            int progressX = this.width / 2 - PROGRESS_BAR_WIDTH / 2;
            int progressY = this.height / 2 + 30;

            VersionedText.drawTexture(PROGRESS_BAR_EMPTY_TEXTURE, matrices, progressX, progressY, 0, 0, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
            VersionedText.drawTexture(PROGRESS_BAR_FULL_TEXTURE, matrices, progressX, progressY, 0, 0, (int) (PROGRESS_BAR_WIDTH * getDownloadScale()), PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);

            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, percentage, this.width / 2, this.height / 2 + 36, 16777215);
            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, speed, this.width / 2, this.height / 2 + 60, 16777215);

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
}
