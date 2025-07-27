package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import pl.skidam.automodpack.client.audio.AudioManager;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

public class ErrorScreen extends VersionedScreen {
    private final String[] errorMessages;
    private Button backButton;

    public ErrorScreen(String... errorMessages) {
        super(VersionedText.literal("ErrorScreen"));
        this.errorMessages = errorMessages;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addRenderableWidget(backButton);
    }

    private void initWidgets() {
        backButton = buttonWidget(this.width / 2 - 100, this.height / 2 + 50, 200, 20, VersionedText.translatable("automodpack.back"), button -> {
            assert minecraft != null;
            minecraft.setScreen(null);
        });
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("[AutoModpack] Error! ").append(VersionedText.translatable("automodpack.error").withStyle(ChatFormatting.RED)), this.width / 2, this.height / 2 - 50, TextColors.WHITE);
        for (int i = 0; i < this.errorMessages.length; i++) {
            drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable(this.errorMessages[i]), this.width / 2, this.height / 2 - 20 + i * 12, 14687790);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
