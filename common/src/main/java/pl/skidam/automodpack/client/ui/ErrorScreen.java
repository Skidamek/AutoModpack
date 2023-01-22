package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.TextHelper;

public class ErrorScreen extends Screen {
    private final String[] errorMessage;

    public ErrorScreen(String... errorMessage) {
        super(TextHelper.literal(""));
        this.errorMessage = errorMessage;
    }

    @Override
    protected void init() {
        super.init();
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 100, this.height / 4 + 96, 200, 20, TextHelper.translatable("gui.toMenu"), (buttonWidget) -> {
            client.setScreen(null);
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, TextHelper.literal("[AutoModpack] Error! Something went wrong!").formatted(Formatting.RED), this.width / 2, this.height / 2 - 40, 16777215);
        for (int i = 0; i < this.errorMessage.length; i++) {
            drawCenteredText(matrices, this.textRenderer, this.errorMessage[i], this.width / 2, this.height / 2 - 20 + i * 10, 14687790);
        }
        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public Text getTitle() {
        return TextHelper.literal("ErrorScreen");
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
