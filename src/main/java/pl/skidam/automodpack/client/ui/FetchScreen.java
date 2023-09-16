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

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.FetchManager;

import static pl.skidam.automodpack_common.GlobalVariables.LOGGER;

public class FetchScreen extends VersionedScreen {

    private ButtonWidget cancelButton;
    private final FetchManager fetchManager;

    public FetchScreen(FetchManager fetchManager) {
        super(VersionedText.literal("FetchScreen"));
        this.fetchManager = fetchManager;
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(cancelButton);
    }

    private void initWidgets() {
        cancelButton = buttonWidget(this.width / 2 - 60, this.height / 2 + 80, 120, 20, VersionedText.translatable("automodpack.cancel"),
                button -> {
                    cancelButton.active = false;
                    cancelFetch();
                }
        );
    }

    private int getFetchesDone() {
        if (fetchManager == null) {
            return -1;
        }
        return fetchManager.fetchesDone;
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);

        if (fetchManager == null) {
            cancelButton.active = false;
        }

        // Fetching direct url's from Modrinth and CurseForge.
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.fetch").formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.wait"), this.width / 2, this.height / 2 - 48, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.fetch.found", getFetchesDone()), this.width / 2, this.height / 2 - 30, 16777215);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    public void cancelFetch() {
        try {
            if (fetchManager != null) {
                fetchManager.cancelAllAndShutdown();
            }

            LOGGER.info("Fetch canceled");

            new ScreenManager().title();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
