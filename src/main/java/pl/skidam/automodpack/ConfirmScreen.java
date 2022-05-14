package pl.skidam.automodpack;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;

@Environment(EnvType.CLIENT)
public class ConfirmScreen extends Screen {
    private Screen parent;

    public ConfirmScreen() {
        super(new TranslatableText("gui.automodpack.screen.title").formatted(Formatting.BOLD));
    }

    @Override
    protected void init() {
        client.setScreen(parent);
        addDrawableChild(new ButtonWidget(width / 2 - 155, height / 6 + 48 - 6 + 75, 150, 20, new TranslatableText("gui.automodpack.screen.button.cancel").formatted(Formatting.GREEN), (button) -> {
            client.setScreen(parent);
        }));
        addDrawableChild(new ButtonWidget(width / 2 + 5, height / 6 + 48 - 6 + 75, 150, 20, new TranslatableText("gui.automodpack.screen.button.quit").formatted(Formatting.RED), (button) -> {
            client.scheduleStop();
        }));
    }

    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawCenteredText(matrices, textRenderer, title, width / 2, 55, 16777215);
        drawCenteredText(matrices, textRenderer, new TranslatableText("gui.automodpack.screen.description"), width / 2, 80, 16777215);
        drawCenteredText(matrices, textRenderer, new TranslatableText("gui.automodpack.screen.secDescription"), width / 2, 90, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }
}