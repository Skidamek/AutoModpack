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
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_server.modpack.Modpack;

import java.nio.file.Path;

//#if MC < 12000
import net.minecraft.client.util.math.MatrixStack;
//#else
//$$import net.minecraft.client.gui.DrawContext;
//#endif

public class ListEntry extends AlwaysSelectedEntryListWidget.Entry<ListEntry> {

    protected final MinecraftClient client;
    public final Modpack.ModpackObject modpack;
    public final Path modpackPath;
    private final MutableText text;
    private final String mainPageUrl;
    private final boolean bigFont;

    public ListEntry(MutableText text, String mainPageUrl, boolean bigFont, Modpack.ModpackObject modpack, Path modpackPath, MinecraftClient client) {
        this.text = text;
        this.mainPageUrl = mainPageUrl;
        this.modpack = modpack;
        this.modpackPath = modpackPath;
        this.client = client;
        this.bigFont = bigFont;
    }

    public ListEntry(MutableText text, boolean bigFont, Modpack.ModpackObject modpack, Path modpackPath, MinecraftClient client) {
        this.text = text;
        this.mainPageUrl = null;
        this.modpack = modpack;
        this.modpackPath = modpackPath;
        this.client = client;
        this.bigFont = bigFont;
    }

//#if MC >= 11700
    @Override
    public Text getNarration() {
        return text;
    }
//#endif

    @Override
//#if MC < 12000
    public void render(MatrixStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
        VersionedMatrices versionedMatrices = new VersionedMatrices();
//#else
//$$public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
//$$    VersionedMatrices versionedMatrices = new VersionedMatrices(this.client, context.getVertexConsumers());
//#endif
        versionedMatrices.push();

        int centeredX = x + entryWidth / 2;
        if (bigFont) {
            float scale = 1.5f;
            versionedMatrices.scale(scale, scale, scale);
            centeredX = (int) (centeredX / scale);
        }

        VersionedText.drawCenteredTextWithShadow(versionedMatrices, client.textRenderer, text, centeredX, y, 16777215);

        versionedMatrices.pop();
    }

    public MutableText getText() {
        return this.text;
    }

    public String getMainPageUrl() {
        return mainPageUrl;
    }

    @Nullable
    public Modpack.ModpackObject getModpack() {
        return modpack;
    }

    @Nullable
    public Path getModpackPath() {
        return modpackPath;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int delta) {
        return !bigFont;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }
}
