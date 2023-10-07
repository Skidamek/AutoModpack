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

package pl.skidam.automodpack.client.ui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_server.modpack.Modpack;

import java.nio.file.Path;
import java.util.Map;

import static pl.skidam.automodpack_common.GlobalVariables.clientConfig;

//#if MC < 12000
//$$ import net.minecraft.client.util.math.MatrixStack;
//#else
import net.minecraft.client.gui.DrawContext;
//#endif

public class ListEntryWidget extends AlwaysSelectedEntryListWidget<ListEntry> {

    private boolean scrolling;

    public ListEntryWidget(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
        super(client, width, height, top, bottom, itemHeight);
        this.centerListVertically = true;

        Map<Path, Modpack.ModpackObject> modpacks = Modpack.getModpacksMap();
        Modpack.setModpackObject(modpacks);
        String selectedModpack = clientConfig.selectedModpack;

        this.clearEntries();

        if (modpacks == null || modpacks.isEmpty()) {
            ListEntry entry = new ListEntry(VersionedText.literal("No modpacks found").formatted(Formatting.BOLD), true, null, null, this.client);
            this.addEntry(entry);
            return;
        }

        for (Map.Entry<Path, Modpack.ModpackObject> modpack : modpacks.entrySet()) {

            Modpack.ModpackObject modpackObject = modpack.getValue();

            String modpackName = modpackObject.getName();
            Path modpackPath = modpack.getKey();

            MutableText text = VersionedText.literal(modpackName);
            if (modpackName.isEmpty()) {
                text = VersionedText.literal(String.valueOf(modpackPath.getFileName()));
            }

            String folderName = modpack.getKey().getFileName().toString();
            if (folderName.equals(selectedModpack)) {
                text = text.formatted(Formatting.BOLD);
            }

            ListEntry entry = new ListEntry(text, false, modpackObject, modpackPath, this.client);

            this.addEntry(entry);

            if (folderName.equals(selectedModpack)) {
                this.setSelected(entry);
            }
        }
    }

    public ListEntryWidget(Map<String, String> changelogs, MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
        super(client, width, height, top, bottom, itemHeight);
        this.centerListVertically = true;

        this.clearEntries();

        if (changelogs == null || changelogs.isEmpty()) {
            ListEntry entry = new ListEntry(VersionedText.literal("No changelogs found").formatted(Formatting.BOLD), true, null, null, this.client);
            this.addEntry(entry);
            return;
        }

        for (Map.Entry<String, String> changelog : changelogs.entrySet()) {
            String textString = changelog.getKey();
            String mainPageUrl = changelog.getValue();

            MutableText text = VersionedText.literal(textString);

            if (textString.startsWith("+")) {
                text = text.formatted(Formatting.GREEN);
            } else if (textString.startsWith("-")) {
                text = text.formatted(Formatting.RED);
            }

            ListEntry entry = new ListEntry(text, mainPageUrl, false, null, null, this.client);
            this.addEntry(entry);
        }
    }

    @Override
//#if MC < 12000
//$$     public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
//#else
public void render(DrawContext matrices, int mouseX, int mouseY, float delta) {
//#endif

        super.render(matrices, mouseX, mouseY, delta);
    }

    public final ListEntry getEntryAtPos(double x, double y) {
        int int_5 = MathHelper.floor(y - (double) this.top) - this.headerHeight + (int) this.getScrollAmount() - 4;
        int index = int_5 / this.itemHeight;
        return x < (double) this.getScrollbarPositionX() && x >= (double) getRowLeft() && x <= (double) (getRowLeft() + getRowWidth()) && index >= 0 && int_5 >= 0 && index < this.getEntryCount() ? this.children().get(index) : null;
    }

    @Override
    protected void updateScrollingState(double mouseX, double mouseY, int button) {
        super.updateScrollingState(mouseX, mouseY, button);
        this.scrolling = button == 0 && mouseX >= (double) this.getScrollbarPositionX() && mouseX < (double) (this.getScrollbarPositionX() + 6);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.updateScrollingState(mouseX, mouseY, button);
        if (!this.isMouseOver(mouseX, mouseY)) {
            return false;
        } else {
            ListEntry entry = this.getEntryAtPos(mouseX, mouseY);
            if (entry != null) {
                if (entry.mouseClicked(mouseX, mouseY, button)) {
                    this.setFocused(entry);
                    this.setSelected(entry);
                    this.setDragging(true);
                    return true;
                }
            }

            return this.scrolling;
        }
    }

    @Override
    public void setSelected(ListEntry entry) {
        super.setSelected(entry);
        if (entry != null) {
            this.centerScrollOn(entry);
        }
    }

    @Override
    protected int getScrollbarPositionX() {
        return this.width - 6;
    }

    @Override
    public int getRowWidth() {
        return super.getRowWidth() + 120;
    }
}
