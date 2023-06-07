package pl.skidam.automodpack.client.ui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ElementListWidget;

public class ScrollingListWidget<E extends ElementListWidget.Entry<E>> extends ElementListWidget<E> {

    public ScrollingListWidget(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
        super(client, width, height, top, bottom, itemHeight);
        this.centerListVertically = true;
    }

    @Override
    public int getRowWidth() {
        return this.width;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        double scale = this.client.getWindow().getScaleFactor();

        RenderSystem.enableScissor((int) (this.left * scale), (int) (this.client.getWindow().getFramebufferHeight() - ((this.top + this.height) * scale)), (int) (this.width * scale), (int) (this.height * scale));
        super.render(context, mouseX, mouseY, delta);
        RenderSystem.disableScissor();
    }

    @Override
    public void appendNarrations(NarrationMessageBuilder builder) {
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }
}