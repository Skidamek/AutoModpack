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
import pl.skidam.automodpack_common.GlobalVariables;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.ModpackUtils;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.ListEntryWidget;
import pl.skidam.automodpack.client.ui.widget.ListEntry;
import pl.skidam.automodpack_common.config.ConfigTools;
import pl.skidam.automodpack_common.config.Jsons;
import pl.skidam.automodpack_server.modpack.Modpack;
import pl.skidam.automodpack_common.utils.CustomFileUtils;

import java.nio.file.Path;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class MenuScreen extends VersionedScreen {
    private ListEntryWidget ListEntryWidget;
    private ButtonWidget selectButton;
    private ButtonWidget redownloadButton;
    private ButtonWidget removeButton;
    private ButtonWidget backButton;


    public MenuScreen() {
        super(VersionedText.literal("MenuScreen"));
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(ListEntryWidget);

        this.addDrawableChild(selectButton);
        this.addDrawableChild(redownloadButton);
        this.addDrawableChild(removeButton);
        this.addDrawableChild(backButton);
    }

    private void initWidgets() {
        this.ListEntryWidget = new ListEntryWidget(this.client, this.width, this.height, 48, this.height - 50, 20);

        int numButtons = 4;
        int buttonWidth = (int) (this.width / (numButtons + 0.75));
        int spacing = (int) (buttonWidth / (numButtons + 1));

        int centerX = this.width / 2 - (numButtons * buttonWidth + (numButtons - 1) * spacing) / 2;

        int button1X = centerX;
        int button2X = centerX + buttonWidth + spacing;
        int button3X = centerX + 2 * (buttonWidth + spacing);
        int button4X = centerX + 3 * (buttonWidth + spacing);


        this.backButton = buttonWidget(button1X, this.height - 35, buttonWidth, 20, VersionedText.translatable("automodpack.back"), button -> {
            assert this.client != null;
            this.client.setScreen(null);
        });

        this.selectButton = buttonWidget(button2X, this.height - 35, buttonWidth, 20, VersionedText.translatable("automodpack.select"), button -> {
            GlobalVariables.LOGGER.info("Select modpack {} from {}", getModpack().getName(), getModpackPath());
            selectModpack(getModpackPath(), getModpack());
        });

        this.redownloadButton = buttonWidget(button3X, this.height - 35, buttonWidth, 20, VersionedText.translatable("automodpack.redownload"), button -> {
            GlobalVariables.LOGGER.info("Redownload {} from {}", getModpack().getName(), getModpack().getLink());
            reDownloadModpack(getModpackPath(), getModpack());
        });

        this.removeButton = buttonWidget(button4X, this.height - 35, buttonWidth, 20, VersionedText.translatable("automodpack.delete"), button -> {
            GlobalVariables.LOGGER.info("Remove modpack {} from {}", getModpack().getName(), getModpackPath());
            removeModpack(getModpackPath());
        });

    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices, mouseX, mouseY, delta);

        this.ListEntryWidget.render(matrices, mouseX, mouseY, delta);
        activateOrDeactivateButtons();
    }

    public void activateOrDeactivateButtons() {
        ListEntry modpackEntry = this.ListEntryWidget.getSelectedOrNull();
        if (modpackEntry == null || getModpack() == null || getModpackPath() == null) {
            this.selectButton.active = false;
            this.redownloadButton.active = false;
            this.removeButton.active = false;
        } else {
            this.redownloadButton.active = true;
            this.removeButton.active = true;

            String currentModpack = clientConfig.selectedModpack;
            String selectedModpack = modpackEntry.getModpackPath().getFileName().toString();
            this.selectButton.active = !currentModpack.equals(selectedModpack);
        }
    }

    public Modpack.ModpackObject getModpack() {
        return this.ListEntryWidget.getSelectedOrNull().getModpack();
    }

    public Path getModpackPath() {
        return this.ListEntryWidget.getSelectedOrNull().getModpackPath();
    }

    private void reDownloadModpack(Path modpackPath, Modpack.ModpackObject modpack) {
        String modpackLink = modpack.getLink();
        Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(modpackLink);

        CustomFileUtils.forceDelete(modpackPath);

        new ModpackUpdater().startModpackUpdate(serverModpackContent, modpackLink, modpackPath);

        GlobalVariables.LOGGER.info("Redownloaded modpack {} from {}", modpack.getName(), modpackLink);
    }

    private void removeModpack(Path modpackPath) {
        String currentModpack = clientConfig.selectedModpack;
        if (currentModpack.equals(modpackPath.getFileName().toString())) {
            clientConfig.selectedModpack = "";
            ConfigTools.saveConfig(clientConfigFile, clientConfig);
        }

        CustomFileUtils.forceDelete(modpackPath);
        // TODO: remove modpack from minecraft files
    }

    private void selectModpack(Path modpackPath, Modpack.ModpackObject modpack) {
        String selectedModpack = modpackPath.getFileName().toString();
        if (clientConfig.selectedModpack.equals(selectedModpack)) {
            return;
        }
        clientConfig.selectedModpack = selectedModpack;
        ConfigTools.saveConfig(clientConfigFile, clientConfig);
    }
}