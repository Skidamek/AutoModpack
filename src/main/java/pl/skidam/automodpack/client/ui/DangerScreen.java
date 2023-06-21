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

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.StaticVariables.LOGGER;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

public class DangerScreen extends VersionedScreen {
    private final Screen parent;
    private final String link;
    private final Path modpackDir;
    private final Path modpackContentFile;

    public DangerScreen(Screen parent, String link, Path modpackDir, Path modpackContentFile) {
        super(VersionedText.common.literal("DangerScreen"));
        this.parent = parent;
        this.link = link;
        this.modpackDir = modpackDir;
        this.modpackContentFile = modpackContentFile;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    @Override
    protected void init() {
        super.init();
        assert this.client != null;

        this.addDrawableChild(VersionedText.buttonWidget(this.width / 2 - 115, this.height / 2 + 50, 120, 20, VersionedText.common.translatable("automodpack.danger.cancel").formatted(Formatting.RED), button -> {
            LOGGER.error("User canceled download, setting his to screen " + parent.getTitle().getString());
            this.client.setScreen(parent);
        }));

        this.addDrawableChild(VersionedText.buttonWidget(this.width / 2 + 15, this.height / 2 + 50, 120, 20, VersionedText.common.translatable("automodpack.danger.confirm").formatted(Formatting.GREEN), button -> {
            CompletableFuture.runAsync(() -> {
                ModpackUpdater.ModpackUpdaterMain(link, modpackDir, modpackContentFile);
            });
        }));
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.danger").formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, 16777215);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.danger.description"), this.width / 2, this.height / 2 - 35, 16777215);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.danger.secDescription"), this.width / 2, this.height / 2 - 25, 16777215);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
