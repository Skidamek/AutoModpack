package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.audio.AudioManager;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

public class ErrorScreen extends VersionedScreen {
    private final String[] errorMessage;
    private ButtonWidget backButton;

    public ErrorScreen(String... errorMessage) {
        super(VersionedText.literal("ErrorScreen"));
        this.errorMessage = errorMessage;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(backButton);
    }

    private void initWidgets() {
        backButton = buttonWidget(this.width / 2 - 100, this.height / 2 + 50, 200, 20, VersionedText.translatable("automodpack.back"), button -> {
            assert client != null;
            client.setScreen(null);
        });
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        // Something went wrong!
        // TODO fix this text
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.literal("[AutoModpack] Error! ").append(VersionedText.translatable("automodpack.error").formatted(Formatting.RED)), this.width / 2, this.height / 2 - 40, 16777215);
        for (int i = 0; i < this.errorMessage.length; i++) {
            drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable(this.errorMessage[i]), this.width / 2, this.height / 2 - 20 + i * 10, 14687790);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
