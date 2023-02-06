package pl.skidam.automodpack.client.ui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.util.math.MatrixStack;

public class ScrollingListWidget<E extends EntryListWidget.Entry<E>> extends EntryListWidget<E> {

    public ScrollingListWidget(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
        super(client, width, height, top, bottom, itemHeight);
        this.centerListVertically = true;
    }

    @Override
    public int getRowWidth() {
        return this.width;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        double scale = this.client.getWindow().getScaleFactor();

        RenderSystem.enableScissor((int) (this.left * scale), (int) (this.client.getWindow().getFramebufferHeight() - ((this.top + this.height) * scale)), (int) (this.width * scale), (int) (this.height * scale));
        super.render(matrices, mouseX, mouseY, delta);
        RenderSystem.disableScissor();
    }

    @Override
    public void appendNarrations(NarrationMessageBuilder builder) {

    }
}