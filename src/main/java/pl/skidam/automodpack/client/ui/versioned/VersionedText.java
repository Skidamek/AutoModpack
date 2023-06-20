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

package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.MinecraftClient; // 1.16
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;

public class VersionedText {

//#if MC >= 12000
//$$
//$$    public static void drawCenteredTextWithShadow(VersionedMatrices matrices, TextRenderer textRenderer, MutableText text, int centerX, int y, int color) {
//$$        matrices.drawCenteredTextWithShadow(textRenderer, text, centerX, y, color);
//$$    }
//$$
//#else

    public static void drawCenteredTextWithShadow(VersionedMatrices matrices, TextRenderer textRenderer, MutableText text, int centerX, int y, int color) {
        textRenderer.drawWithShadow(matrices, text, (float)(centerX - textRenderer.getWidth(text) / 2), (float)y, color);
    }

//#endif

    // It needs to be in subclass to don't crash server while using texts
    // Because then it would load this whole class with matrices and yeah... crash
public static class common {
//#if MC < 11902
//$$
    public static MutableText translatable(String key, Object... args) {
        return new TranslatableText(key, args);
    }

    public static MutableText literal(String string) {
        return new LiteralText(string);
    }

//#else
//$$
//$$public static MutableText translatable(String key, Object... args) {
//$$    return Text.translatable(key, args);
//$$}
//$$
//$$public static MutableText literal(String string) {
//$$    return Text.literal(string);
//$$}
//$$
//#endif
}





//#if MC < 11903
    public static ButtonWidget buttonWidget(int x, int y, int width, int height, Text message, ButtonWidget.PressAction onPress) {
        return new ButtonWidget(x, y, width, height, message, onPress);
    }
//#else
//$$
//$$public static ButtonWidget buttonWidget(int x, int y, int width, int height, Text message, ButtonWidget.PressAction onPress) {
//$$    return ButtonWidget.builder(message, onPress).position(x, y).size(width, height).build();
//$$}
//$$
//#endif

//#if MC < 12000
//$$
    public static void drawTexture(Identifier textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int progressBarWidth, int progressBarHeight) {
//#if MC >= 11700
        RenderSystem.setShaderTexture(0, textureID);
//#else
//$$    MinecraftClient.getInstance().getTextureManager().bindTexture(textureID);
//#endif
        DrawableHelper.drawTexture(matrices, x, y, u, v, width, height, progressBarWidth, progressBarHeight);
    }
//$$
//#else
//$$
//$$public static void drawTexture(Identifier textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int progressBarWidth, int progressBarHeight) {
//$$    matrices.drawTexture(textureID, x, y, u, v, width, height, progressBarWidth, progressBarHeight);
//$$}
//$$
//#endif
}
