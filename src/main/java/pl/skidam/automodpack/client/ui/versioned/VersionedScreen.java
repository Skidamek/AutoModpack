package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;


//#if MC <= 1165
//$$import net.minecraft.client.MinecraftClient;
//$$import net.minecraft.client.gui.Element;
//$$import net.minecraft.client.gui.widget.ClickableWidget;
//#endif

//#if MC < 1200
//$$ import net.minecraft.client.util.math.MatrixStack;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//#endif

public class VersionedScreen extends Screen {

    protected VersionedScreen(Text title) {
        super(title);
    }

//#if MC < 1200
//$$    @Override
//$$    public void render(MatrixStack matrix, int mouseX, int mouseY, float delta) {
//$$        VersionedMatrices matrices = new VersionedMatrices();
//#else
    @Override
    public void render(DrawContext matrix, int mouseX, int mouseY, float delta) {
        VersionedMatrices matrices = new VersionedMatrices(this.client, matrix.getVertexConsumers());
//#endif

// Render background
//#if MC < 1202
//$$    super.renderBackground(matrices);
//#else if MC < 1206
//$$    super.renderBackground(matrices, mouseX, mouseY, delta);
//#endif

        super.render(matrices, mouseX, mouseY, delta);

        // Render the rest of our screen
        versionedRender(matrices, mouseX, mouseY, delta);
    }

    // This method is to be override by the child classes
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) { }


//#if MC <= 1165
//$$    public <T extends Element> void addDrawableChild(T child) {
//$$       if (child instanceof ClickableWidget) {
//$$           super.addButton((ClickableWidget) child);
//$$           return;
//$$       }
//$$       super.addChild(child);
//$$   }
//#endif


//#if MC >= 1200

   public static void drawCenteredTextWithShadow(VersionedMatrices matrices, TextRenderer textRenderer, MutableText text, int centerX, int y, int color) {
       matrices.drawCenteredTextWithShadow(textRenderer, text, centerX, y, color);
   }

//#else
//$$
//$$     public static void drawCenteredTextWithShadow(VersionedMatrices matrices, TextRenderer textRenderer, MutableText text, int centerX, int y, int color) {
//$$         textRenderer.drawWithShadow(matrices, text, (float)(centerX - textRenderer.getWidth(text) / 2), (float)y, color);
//$$     }
//$$
//#endif


//#if MC < 1193
//$$     public static ButtonWidget buttonWidget(int x, int y, int width, int height, Text message, ButtonWidget.PressAction onPress) {
//$$         return new ButtonWidget(x, y, width, height, message, onPress);
//$$     }
//#else

public static ButtonWidget buttonWidget(int x, int y, int width, int height, Text message, ButtonWidget.PressAction onPress) {
   return ButtonWidget.builder(message, onPress).position(x, y).size(width, height).build();
}

//#endif


//#if MC < 1200
//$$
//$$     public static void drawTexture(Identifier textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
            //#if MC <= 1165
//$$        MinecraftClient.getInstance().getTextureManager().bindTexture(textureID);
            //#else
//$$         RenderSystem.setShaderTexture(0, textureID);
            //#endif
//$$         DrawableHelper.drawTexture(matrices, x, y, u, v, width, height, textureWidth, textureHeight);
//$$     }
//$$
//#else

public static void drawTexture(Identifier textureID, VersionedMatrices matrices, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
   matrices.drawTexture(textureID, x, y, u, v, width, height, textureWidth, textureHeight);
}

//#endif
}
