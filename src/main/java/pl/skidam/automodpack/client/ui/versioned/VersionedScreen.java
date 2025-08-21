package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/*? if >=1.21.6 {*/
import net.minecraft.client.renderer.RenderPipelines;
/*?} else if >=1.21.2 {*/
/*import net.minecraft.client.renderer.RenderType;
import java.util.function.Function;
*//*?}*/

/*? if <1.20 {*/
/*import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
*//*?} else {*/
import net.minecraft.client.gui.GuiGraphics;
/*?}*/

public class VersionedScreen extends Screen {

    protected VersionedScreen(Component title) {
        super(title);
    }

    /*? if <1.20 {*/
    /*@Override
    public void render(PoseStack matrix, int mouseX, int mouseY, float delta) {
        VersionedMatrices matrices = new VersionedMatrices();
    *//*?} else {*/
    @Override
    public void render(GuiGraphics matrix, int mouseX, int mouseY, float delta) {
        VersionedMatrices matrices = new VersionedMatrices(matrix);
        /*?}*/

        // Render background
        /*? if <1.20.2 {*/
        /*super.renderBackground(matrices.getContext());
         *//*?} elif <1.20.6 {*/
        /*super.renderBackground(matrices.getContext(), mouseX, mouseY, delta);
         *//*?} else {*/
        super.render(matrix, mouseX, mouseY, delta);
        /*?}*/

        // Render the rest of our screen
        versionedRender(matrices, mouseX, mouseY, delta);

        /*? if <1.20.6 {*/
        /*super.render(matrices.getContext(), mouseX, mouseY, delta);
         *//*?}*/
    }

    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) { }

    /*? if <=1.16.5 {*//*
    public <T extends Element> void addDrawableChild(T child) {
        if (child instanceof ClickableWidget) {
            super.addButton((ClickableWidget) child);
            return;
        }
        super.addChild(child);
    }
    *//*?} elif <1.19.3 {*/
    public void addDrawableChild(Button button) {
        this.addButton(button);
    }
    /*?}*/

    /*? if >=1.20 {*/
    public static void drawCenteredTextWithShadow(VersionedMatrices matrices, Font textRenderer, MutableComponent text, int centerX, int y, int color) {
        matrices.getContext().drawCenteredString(textRenderer, text, centerX, y, color);
    }
    /*?} else {*/
    /*public static void drawCenteredTextWithShadow(VersionedMatrices matrices, Font textRenderer, MutableComponent text, int centerX, int y, int color) {
        textRenderer.drawShadow(matrices.getContext(), text, (float)(centerX - textRenderer.width(text) / 2), (float)y, color);
    }
    *//*?}*/

    /*? if <1.19.3 {*/
    /*public static Button buttonWidget(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        return new Button(x, y, width, height, message, onPress);
    }
    *//*?} else {*/
    public static Button buttonWidget(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        return Button.builder(message, onPress).pos(x, y).size(width, height).build();
    }
    /*?}*/

    /*? if <=1.20 {*/
    /*public static void drawTexture(ResourceLocation textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
        /^? if <=1.16.5 {^/
        /^Minecraft.getInstance().getTextureManager().bindTexture(textureID);
        ^//^?} else {^/
        RenderSystem.setShaderTexture(0, textureID);
        /^?}^/
        GuiComponent.blit(matrices.getContext(), x, y, u, v, width, height, textureWidth, textureHeight);
    }
    *//*?} else {*/
    public static void drawTexture(ResourceLocation textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
        /*? if >=1.21.6 {*/
        matrices.getContext().blit(RenderPipelines.GUI_TEXTURED, textureID, x, y, u, v, width, height, textureWidth, textureHeight);
        /*?} elif >=1.21.2 {*/
        /*Function<ResourceLocation, RenderType> RenderTypes = RenderType::guiTextured;
        matrices.getContext().blit(RenderTypes, textureID, x, y, u, v, width, height, textureWidth, textureHeight);
        *//*?} else {*/
        /*matrices.getContext().blit(textureID, x, y, u, v, width, height, textureWidth, textureHeight);
         *//*?}*/
    }
    /*?}*/

    public net.minecraft.client.Minecraft getClient() {
        /*? if >=1.20 {*/
        return this.minecraft;
        /*?} else {*/
        return this.minecraft;
        /*?}*/
    }

    public net.minecraft.client.gui.Font getTextRenderer() {
        /*? if >=1.20 {*/
        return this.minecraft.font;
        /*?} else {*/
        return this.font;
        /*?}*/
    }

    public net.minecraft.client.gui.screens.Screen getMinecraftScreen() {
        return (net.minecraft.client.gui.screens.Screen) this;
    }
}