package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.Formatting;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.GlobalVariables;

public class ValidationScreen extends VersionedScreen {
    private final Screen parent;
    private final String serverFingerprint;
    private final Runnable validatedCallback;
    private final Runnable canceledCallback;
    private boolean validated = false;
    private TextFieldWidget textField;
    private ButtonWidget backButton;
    private ButtonWidget validateButton;

    public ValidationScreen(Screen parent, String serverFingerprint, Runnable validatedCallback,
                            Runnable canceledCallback) {
        super(VersionedText.literal("RestartScreen"));
        this.parent = parent;
        this.serverFingerprint = serverFingerprint;
        this.validatedCallback = validatedCallback;
        this.canceledCallback = canceledCallback;
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(this.textField);
        this.addDrawableChild(this.backButton);
        this.addDrawableChild(this.validateButton);
        this.setInitialFocus(this.textField);
    }

    public void initWidgets() {
        assert this.client != null;
        this.textField = new TextFieldWidget(this.textRenderer, this.width / 2 - 150, this.height / 2 - 30, 300, 20,
                VersionedText.literal("")
        );
        this.textField.setMaxLength(64); // default is 30 which is too little

        this.backButton = buttonWidget(this.width / 2 - 155, this.height / 2 + 50, 150, 20,
                VersionedText.translatable("automodpack.back"),
                button -> {
                    if (!this.validated) {
                        this.canceledCallback.run();
                    }
                    this.client.setScreen(null);
                }
        );

        this.validateButton = buttonWidget(this.width / 2 + 5, this.height / 2 + 50, 150, 20,
                VersionedText.translatable("automodpack.validation.run"),
                button -> validate(textField.getText()));
    }

    private void validate(String input) {
        input = input.strip();
        if (input.equals(serverFingerprint) || input.equals("I AM INCREDIBLY STUPID")) {
            validateButton.active = false;
            this.validated = true;
            validatedCallback.run();
        } else {
            GlobalVariables.LOGGER.error("Server fingerprint validation failed, try again");
        }
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.validation.text").formatted(Formatting.BOLD),
                this.width / 2, this.height / 2 - 100, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.validation.description"),
                this.width / 2, this.height / 2 - 75, 16777215);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}