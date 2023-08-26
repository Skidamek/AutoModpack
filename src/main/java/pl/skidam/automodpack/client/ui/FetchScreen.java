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

import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

public class FetchScreen extends VersionedScreen {

    private ButtonWidget cancelButton;

    public FetchScreen() {
        super(VersionedText.common.literal("FetchScreen"));
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(cancelButton);
    }

    private void initWidgets() {
        cancelButton = VersionedText.buttonWidget(this.width / 2 - 60, this.height / 2 + 80, 120, 20, VersionedText.common.translatable("automodpack.cancel"),
                button -> {
                    cancelButton.active = false;
                    ModpackUpdater.cancelDownload();
                }
        );
    }

    private int getFetchesDone() {
        if (ModpackUpdater.fetchManager == null) {
            return -1;
        }
        return ModpackUpdater.fetchManager.fetchesDone;
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);

        if (ModpackUpdater.fetchManager == null) {
            cancelButton.active = false;
        }

        // Fetching direct url's from Modrinth and CurseForge.
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.fetch").formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, 16777215);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.wait"), this.width / 2, this.height / 2 - 48, 16777215);
        VersionedText.drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.common.translatable("automodpack.fetch.found", getFetchesDone()), this.width / 2, this.height / 2 - 30, 16777215);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
