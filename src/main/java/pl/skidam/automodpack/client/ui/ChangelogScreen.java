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
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.Util;
import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.widget.ListEntry;
import pl.skidam.automodpack.client.ui.widget.ListEntryWidget;
import pl.skidam.automodpack_common.config.ConfigTools;
import pl.skidam.automodpack_common.config.Jsons;
import pl.skidam.automodpack_common.utils.ModpackContentTools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ChangelogScreen extends VersionedScreen {
    private final Screen parent;
    private final Path modpackDir;
    private final Changelogs changelogs;
    private static Map<String, String> formattedChanges;
    private ListEntryWidget listEntryWidget;
    private TextFieldWidget searchField;
    private ButtonWidget backButton;
    private ButtonWidget openMainPageButton;

    public ChangelogScreen(Screen parent, Path modpackDir, Changelogs changelogs) {
        super(VersionedText.literal("ChangelogScreen"));
        this.parent = parent;
        this.modpackDir = modpackDir;
        this.changelogs = changelogs;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    @Override
    protected void init() {
        super.init();

        formattedChanges = reFormatChanges();

        initWidgets();

        this.addDrawableChild(this.listEntryWidget);
        this.addDrawableChild(this.searchField);
        this.addDrawableChild(this.backButton);
        this.addDrawableChild(this.openMainPageButton);
        this.setInitialFocus(this.searchField);
    }

    private void initWidgets() {
        this.listEntryWidget = new ListEntryWidget(formattedChanges, this.client, this.width, this.height, 48, this.height - 50, 20); // 38

        this.searchField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 20, 200, 20,
                VersionedText.literal("")
        );
        this.searchField.setChangedListener((textField) -> updateChangelogs()); // Update the changelogs display based on the search query

        this.backButton = buttonWidget(this.width / 2 - 140, this.height - 30, 140, 20,
                VersionedText.translatable("automodpack.back"),
                button -> this.client.setScreen(this.parent)
        );

        this.openMainPageButton = buttonWidget(this.width / 2 + 20, this.height - 30, 140, 20,
                VersionedText.translatable("automodpack.changelog.openPage"),
                button -> {
                    ListEntry selectedEntry = listEntryWidget.getSelectedOrNull();

                    if (selectedEntry == null) {
                        return;
                    }

                    String mainPageUrl = selectedEntry.getMainPageUrl();
                    Util.getOperatingSystem().open(mainPageUrl);
                }
        );

    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);

        this.listEntryWidget.render(matrices, mouseX, mouseY, delta);

        ListEntry selectedEntry = listEntryWidget.getSelectedOrNull();
        if (selectedEntry != null) {
            this.openMainPageButton.active = selectedEntry.getMainPageUrl() != null;
        } else {
            this.openMainPageButton.active = false;
        }

        // Draw summary of added/removed mods
        drawSummaryOfChanges(matrices);
    }

    private void drawSummaryOfChanges(VersionedMatrices matrices) {

        Path modpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);

        if (modpackContentFile == null) return;

        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        int modsAdded = 0;
        int modsRemoved = 0;
        if (modpackContent == null) return;
        for (Map.Entry<String, String> changelog : changelogs.changesAddedList.entrySet()) {
            String fileType = ModpackContentTools.getFileType(changelog.getKey(), modpackContent);
            if (fileType.equals("mod")) {
                modsAdded++;
            }
        }

        for (Map.Entry<String, String> changelog : changelogs.changesDeletedList.entrySet()) {
            String fileType = ModpackContentTools.getFileType(changelog.getKey(), modpackContent);
            if (fileType.equals("mod")) {
                modsRemoved++;
            }
        }

        String summary = "Mods + " + modsAdded + " | - " + modsRemoved;

        drawCenteredTextWithShadow(matrices, textRenderer, VersionedText.literal(summary), this.width / 2, 5, 16777215);
    }

    private void updateChangelogs() {
        // If the search field is empty, reset the changelogs to the original list
        if (this.searchField.getText().isEmpty()) {
            formattedChanges = reFormatChanges();
        } else {
            // Filter the changelogs based on the search query using a case-insensitive search
            Map<String, String> filteredChangelogs = new HashMap<>();
            for (Map.Entry<String, String> changelog : reFormatChanges().entrySet()) {
                if (changelog.getKey().toLowerCase().contains(this.searchField.getText().toLowerCase())) {
                    filteredChangelogs.put(changelog.getKey(), changelog.getValue());
                }
            }
            formattedChanges = filteredChangelogs;
        }

        // remove method is only available in 1.17+
//#if MC >= 11700
        this.remove(this.listEntryWidget);
        this.remove(this.backButton);
        this.remove(this.openMainPageButton);
//#endif

        this.listEntryWidget = new ListEntryWidget(formattedChanges, this.client, this.width, this.height, 48, this.height - 50, 20); // 38

        this.addDrawableChild(this.listEntryWidget);
        this.addDrawableChild(this.searchField);
        this.addDrawableChild(this.backButton);
        this.addDrawableChild(this.openMainPageButton);
    }

    private Map<String, String> reFormatChanges() {
        Map<String, String> reFormattedChanges = new HashMap<>();

        for (Map.Entry<String, String> changelog : changelogs.changesAddedList.entrySet()) {
            reFormattedChanges.put("+ " + changelog.getKey(), changelog.getValue());
        }

        for (Map.Entry<String, String> changelog : changelogs.changesDeletedList.entrySet()) {
            reFormattedChanges.put("- " + changelog.getKey(), changelog.getValue());
        }

        return reFormattedChanges;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        assert this.client != null;
        this.client.setScreen(this.parent);
        return false;
    }
}


