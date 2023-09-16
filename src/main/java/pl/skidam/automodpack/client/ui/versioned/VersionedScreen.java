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

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

//#if MC <= 11605
//$$import net.minecraft.client.MinecraftClient;
//$$import net.minecraft.client.gui.Element;
//$$import net.minecraft.client.gui.widget.ClickableWidget;
//#endif

//#if MC < 12000
import net.minecraft.client.util.math.MatrixStack;
//#else
//$$import net.minecraft.client.gui.DrawContext;
//#endif

public class VersionedScreen extends Screen {

    protected VersionedScreen(Text title) {
        super(title);
    }

    @Override
//#if MC < 12000
    public void render(MatrixStack matrix, int mouseX, int mouseY, float delta) {
        VersionedMatrices matrices = new VersionedMatrices();
//#else
//$$public void render(DrawContext matrix, int mouseX, int mouseY, float delta) {
//$$    VersionedMatrices matrices = new VersionedMatrices(this.client, matrix.getVertexConsumers());
//#endif
        versionedRender(matrices, mouseX, mouseY, delta);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) { }


//#if MC <= 11605
//$$    public <T extends Element> void addDrawableChild(T child) {
//$$       if (child instanceof ClickableWidget) {
//$$           super.addButton((ClickableWidget) child);
//$$           return;
//$$       }
//$$       super.addChild(child);
//$$   }
//#endif


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
    public static void drawTexture(Identifier textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
        //#if MC <= 11605
        //$$MinecraftClient.getInstance().getTextureManager().bindTexture(textureID);
        //#else
        RenderSystem.setShaderTexture(0, textureID);
        //#endif
        DrawableHelper.drawTexture(matrices, x, y, u, v, width, height, textureWidth, textureHeight);
    }
//$$
//#else
//$$
//$$public static void drawTexture(Identifier textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
//$$    matrices.drawTexture(textureID, x, y, u, v, width, height, textureWidth, textureHeight);
//$$}
//$$
//#endif
}
