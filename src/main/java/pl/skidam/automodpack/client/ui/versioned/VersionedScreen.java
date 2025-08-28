package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/*? if >=1.21.2 {*/
import net.minecraft.client.render.RenderLayer;
import java.util.function.Function;
/*?}*/

/*? if >=1.21.6 {*/
import net.minecraft.client.renderer.RenderPipelines;
/*?}*/

/*? if <1.20 {*/
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
/*?} else {*/
import net.minecraft.client.gui.GuiGraphics;
/*?}*/

public class VersionedScreen extends Screen {

    protected VersionedScreen(Component title) {
        super(title);
    }

    /*? if <1.20 {*/
    @Override
    public void render(PoseStack matrix, int mouseX, int mouseY, float delta) {
        VersionedMatrices matrices = new VersionedMatrices();
        matrices.set(matrix);

        super.render(matrix, mouseX, mouseY, delta);

        versionedRender(matrices, mouseX, mouseY, delta);
    }
    /*?} else {*/
    @Override
    public void render(GuiGraphics matrix, int mouseX, int mouseY, float delta) {
        VersionedMatrices matrices = new VersionedMatrices(matrix);

        super.render(matrix, mouseX, mouseY, delta);

        versionedRender(matrices, mouseX, mouseY, delta);
    }
    /*?}*/

    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) { }

    public <T extends net.minecraft.client.gui.components.AbstractWidget> T addDrawableChild(T widget) {
        return super.addRenderableWidget(widget);
    }

    public static void drawCenteredTextWithShadow(VersionedMatrices matrices, Font textRenderer, MutableComponent text, int centerX, int y, int color) {
        /*? if >=1.20 {*/
        matrices.getContext().drawCenteredString(textRenderer, text, centerX, y, color);
        /*?} else {*/
        textRenderer.drawShadow(matrices.getContext(), text, centerX - textRenderer.width(text) / 2, y, color);
        /*?}*/
    }

    public static Button buttonWidget(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        /*? if >=1.20 {*/
        return Button.builder(message, onPress).pos(x, y).size(width, height).build();
        /*?} else if >=1.19.4 {*/
        return Button.builder(message, onPress).bounds(x, y, width, height).build();
        /*?} else {*/
        return new Button(x, y, width, height, message, onPress);
        /*?}*/
    }

    public static void drawTexture(ResourceLocation textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
        /*? if >=1.21.6 {*/
        matrices.getContext().blit(RenderPipelines.GUI_TEXTURED, textureID, x, y, u, v, width, height, textureWidth, textureHeight);
        /*?} elif >=1.21.2 {*/
        matrices.getContext().blit(RenderLayer::guiTextured, textureID, x, y, (float)u, (float)v, width, height, textureWidth, textureHeight);
        /*?} elif >=1.21 {*/
        matrices.getContext().blit(RenderLayer::guiTextured, textureID, x, y, u, v, width, height, textureWidth, textureHeight);
        /*?} elif >=1.20 {*/
        matrices.getContext().blit(textureID, x, y, u, v, width, height, textureWidth, textureHeight);
        /*?} else {*/
        RenderSystem.setShaderTexture(0, textureID);
        GuiComponent.blit(matrices.getContext(), x, y, u, v, width, height, textureWidth, textureHeight);
        /*?}*/
    }

    public net.minecraft.client.Minecraft getClient() {
        return this.minecraft;
    }

    public Font getTextRenderer() {
        /*? if >=1.20 {*/
        return this.minecraft.font;
        /*?} else {*/
        return this.font;
        /*?}*/
    }

    public Screen getMinecraftScreen() {
        return this;
    }
}