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
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;

import java.nio.file.Path;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

public class RestartScreen extends VersionedScreen {
    private final Path gameDir;
    private final boolean fullDownload;
    private static ButtonWidget cancelButton;
    private static ButtonWidget restartButton;
    private static ButtonWidget changelogsButton;

    public RestartScreen(Path gameDir, boolean fullDownload) {
        super(VersionedText.common.literal("RestartScreen"));
        this.gameDir = gameDir;
        this.fullDownload = fullDownload;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(cancelButton);
        this.addDrawableChild(restartButton);
        this.addDrawableChild(changelogsButton);

        if (ModpackUpdater.changesAddedList.isEmpty() && ModpackUpdater.changesDeletedList.isEmpty()) {
            changelogsButton.active = false;
        }
    }

    public void initWidgets() {
        assert this.client != null;
        cancelButton = VersionedText.buttonWidget(this.width / 2 - 155, this.height / 2 + 50, 150, 20, VersionedText.common.translatable("automodpack.restart.cancel").formatted(Formatting.RED), button -> {
            this.client.setScreen(null);
        });

        restartButton = VersionedText.buttonWidget(this.width / 2 + 5, this.height / 2 + 50, 150, 20, VersionedText.common.translatable("automodpack.restart.confirm").formatted(Formatting.GREEN), button -> {
            new ReLauncher.Restart(gameDir, fullDownload);
        });

        changelogsButton = VersionedText.buttonWidget(this.width / 2 - 75, this.height / 2 + 75, 150, 20, VersionedText.common.translatable("automodpack.changelog.view"), button -> {
            this.client.setScreen(new ChangelogScreen(this, gameDir));
        });
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.restart." + (fullDownload ? "full" : "update")).formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, 16777215);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.restart.description"), this.width / 2, this.height / 2 - 35, 16777215);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.restart.secDescription"), this.width / 2, this.height / 2 - 25, 16777215);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}