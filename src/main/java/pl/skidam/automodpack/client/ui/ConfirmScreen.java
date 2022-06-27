package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.DeleteModpack;

public class ConfirmScreen extends Screen {
    private Screen parent;
    public ConfirmScreen() {
        super(Text.translatable("gui.automodpack.screen.confirm.title"));
    }

    @Override
    protected void init() {
        super.init();
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 50 - 100, this.height / 6 + 48 - 6 + 75, 150, 20, Text.translatable("gui.automodpack.screen.confirm.button.cancel").formatted(Formatting.GREEN), (button) -> {
            this.client.setScreen(parent);
        }));
        this.addDrawableChild(new ButtonWidget(this.width / 2, this.height / 6 + 48 - 6 + 75, 150, 20, Text.translatable("gui.automodpack.screen.confirm.button.sure").formatted(Formatting.RED), (button) -> {
            new DeleteModpack();
            this.client.scheduleStop();
        }));
    }

    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 55, 16777215);
        drawCenteredText(matrices, this.textRenderer, Text.translatable("gui.automodpack.screen.confirm.description"), this.width / 2, 80, 16777215);
        drawCenteredText(matrices, this.textRenderer, Text.translatable("gui.automodpack.screen.confirm.secDescription"), this.width / 2, 90, 16777215);
        drawCenteredText(matrices, this.textRenderer, Text.translatable("gui.automodpack.screen.confirm.thiDescription"), this.width / 2, 100, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean shouldCloseOnEsc() { return false; }
}