package pl.skidam.automodpack.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Objects;

@Environment(EnvType.CLIENT)
public class RestartScreen extends Screen {
    private Screen parent;

    public RestartScreen(Text title) {
        super(title);
    }

    @Override
    protected void init() {
        super.init();
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 50 - 100, this.height / 6 + 48 - 6 + 75, 150, 20, Text.translatable("gui.automodpack.screen.restart.button.cancel").formatted(Formatting.GREEN), (button) -> {
            Objects.requireNonNull(this.client).setScreen(parent);
        }));
        this.addDrawableChild(new ButtonWidget(this.width / 2, this.height / 6 + 48 - 6 + 75, 150, 20, Text.translatable("gui.automodpack.screen.restart.button.quit").formatted(Formatting.RED), (button) -> {
            Objects.requireNonNull(this.client).scheduleStop();
        }));
    }

    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 55, 16777215);
        drawCenteredText(matrices, this.textRenderer, Text.translatable("gui.automodpack.screen.restart.description"), this.width / 2, 80, 16777215);
        drawCenteredText(matrices, this.textRenderer, Text.translatable("gui.automodpack.screen.restart.secDescription"), this.width / 2, 90, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean shouldCloseOnEsc() {
        return false;
    }
}