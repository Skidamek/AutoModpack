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
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.utils.DownloadInfo;

import java.util.ArrayList;
import java.util.List;

import static pl.skidam.automodpack.StaticVariables.MOD_ID;
import static pl.skidam.automodpack.client.ModpackUpdater.downloadInfos;
import static pl.skidam.automodpack.client.ModpackUpdater.getDownloadInfo;
import static pl.skidam.automodpack.utils.RefactorStrings.getFormatedETA;

public class DownloadScreen extends VersionedScreen {

    private static final Identifier PROGRESS_BAR_EMPTY_TEXTURE = new Identifier(MOD_ID, "gui/progress-bar-empty.png");
    private static final Identifier PROGRESS_BAR_FULL_TEXTURE = new Identifier(MOD_ID, "gui/progress-bar-full.png");
    private static final int PROGRESS_BAR_WIDTH = 250;
    private static final int PROGRESS_BAR_HEIGHT = 20;
    private static int ticks = 0;
    private ButtonWidget cancelButton;

    public DownloadScreen() {
        super(VersionedText.common.literal("DownloadScreen"));
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(cancelButton);
    }

    private void initWidgets() {
        cancelButton = VersionedText.buttonWidget(this.width / 2 - 50, this.height - 25, 100, 20, VersionedText.common.translatable("automodpack.cancel"),
                button -> ModpackUpdater.cancelDownload()
        );
    }

    private Text getStage() {
        String stage = ModpackUpdater.getStage();
        return VersionedText.common.literal(stage);
    }

    private Text getPercentage() {
        int percentage = ModpackUpdater.getTotalPercentageOfFileSizeDownloaded();
        return VersionedText.common.literal(percentage + "%");
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
            return VersionedText.common.literal(roundedSpeed + " MB/s");
        }

        return VersionedText.common.translatable("automodpack.download.calculating"); // Calculating...
    }

    private Text getTotalETA() {
        String eta = ModpackUpdater.getTotalETA();
        return VersionedText.common.translatable("automodpack.download.eta", eta); // Time left: %s
    }

    private String getETAOfFile(String file) {
        if (getDownloadInfo(file) == null) return "0s";
        double eta = getDownloadInfo(file).getEta();

        if (eta < 0) return "N/A";

        return getFormatedETA(eta);
    }

    private void drawDownloadingFiles(VersionedMatrices matrices) {
        float scale = 1.0F;
        int y = this.height / 2 - 90;

        matrices.push();
        matrices.scale(scale, scale, scale);

        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);

        if (downloadInfosCopy.size() > 0) {
            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.download.downloading"), (int) (this.width / 2 * scale), y, 16777215);

            // Use a separate variable for the current y position
            int currentY = y + 15;
            for (DownloadInfo file : downloadInfosCopy) {
                if (file == null) continue;
                String fileName = file.getFileName();
                VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.literal( fileName + " (" + getDownloadedSize(fileName) + ")" + " - " + getETAOfFile(fileName)), (int) (this.width / 2 * scale), currentY, 16777215);
                currentY += 10;
            }
        } else {
            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.download.noFiles"), (int) (this.width / 2 * scale), y, 16777215);

            // Please wait...
            VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.wait").formatted(Formatting.BOLD), (int) (this.width / 2 * scale), y + 25, 16777215);
        }

        matrices.pop();
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


    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        MutableText percentage = (MutableText) this.getPercentage();
        MutableText stage = (MutableText) this.getStage();
        MutableText eta = (MutableText) this.getTotalETA();
        MutableText speed = (MutableText) this.getTotalDownloadSpeed();
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, stage, this.width / 2, this.height / 2 - 10, 16777215);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, eta, this.width / 2, this.height / 2 + 10, 16777215);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, percentage, this.width / 2, this.height / 2 + 30, 16777215);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, speed, this.width / 2, this.height / 2 + 80, 16777215);

        drawDownloadingFiles(matrices);

        MutableText modpackName = VersionedText.common.literal(ModpackUpdater.getModpackName()).formatted(Formatting.BOLD);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, modpackName, this.width / 2, this.height / 2 - 110, 16777215);

        // Render progress bar
        int x = this.width / 2 - PROGRESS_BAR_WIDTH / 2;
        int y = this.height / 2 + 50;

        float downloadedScale = (float) ModpackUpdater.getTotalPercentageOfFileSizeDownloaded() / 100;

        VersionedText.drawTexture(PROGRESS_BAR_EMPTY_TEXTURE, matrices, x, y, 0, 0, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
        VersionedText.drawTexture(PROGRESS_BAR_FULL_TEXTURE, matrices, x, y, 0, 0, (int) (PROGRESS_BAR_WIDTH * downloadedScale), PROGRESS_BAR_HEIGHT, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);

        checkAndStartMusic();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
