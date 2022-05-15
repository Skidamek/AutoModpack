package pl.skidam.automodpack;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;

@Environment(EnvType.CLIENT)
public class ConfirmScreen extends Screen {
    private Screen parent;

    public ConfirmScreen(Text title) {
        super(title);
    }

    @Override
    protected void init() {
        super.init();
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 155, this.height / 6 + 48 - 6 + 75, 150, 20, new TranslatableText("gui.automodpack.screen.button.cancel").formatted(Formatting.GREEN), (button) -> {
            this.client.setScreen(parent);
        }));
        this.addDrawableChild(new ButtonWidget(this.width / 2 + 5, this.height / 6 + 48 - 6 + 75, 150, 20, new TranslatableText("gui.automodpack.screen.button.quit").formatted(Formatting.RED), (button) -> {
            this.client.scheduleStop();
        }));
    }

    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 55, 16777215);
        drawCenteredText(matrices, this.textRenderer, new TranslatableText("gui.automodpack.screen.description"), this.width / 2, 80, 16777215);
        drawCenteredText(matrices, this.textRenderer, new TranslatableText("gui.automodpack.screen.secDescription"), this.width / 2, 90, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }
}