package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.audio.AudioManager;

public class ErrorScreen extends Screen {
    private final String[] errorMessage;

    public ErrorScreen(String... errorMessage) {
        super(TextHelper.literal(""));
        this.errorMessage = errorMessage;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    @Override
    protected void init() {
        super.init();
        this.addDrawableChild(ButtonWidget.builder(TextHelper.translatable("automodpack.back"),
                button -> client.setScreen(null)
        ).position(this.width / 2 - 100, this.height / 2 + 50).size(200, 20).build());
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        // Something went wrong!
        drawCenteredTextWithShadow(matrices, this.textRenderer, TextHelper.literal("[AutoModpack] Error! ").append(TextHelper.translatable("automodpack.error").formatted(Formatting.RED)), this.width / 2, this.height / 2 - 40, 16777215);
        for (int i = 0; i < this.errorMessage.length; i++) {
            drawCenteredTextWithShadow(matrices, this.textRenderer, TextHelper.translatable(this.errorMessage[i]), this.width / 2, this.height / 2 - 20 + i * 10, 14687790);
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
