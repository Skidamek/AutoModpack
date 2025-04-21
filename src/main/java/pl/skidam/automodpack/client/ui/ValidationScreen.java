package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
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
    private final Toast failedToast = new SystemToast(SystemToast.Type.PACK_LOAD_FAILURE, VersionedText.translatable("automodpack.validation.failed"), VersionedText.translatable("automodpack.retry"));
    private TextFieldWidget textField;
    private ButtonWidget backButton;
    private ButtonWidget validateButton;

    public ValidationScreen(Screen parent, String serverFingerprint, Runnable validatedCallback,
                            Runnable canceledCallback) {
        super(VersionedText.literal("ValidationScreen"));
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
        this.textField = new TextFieldWidget(this.textRenderer, this.width / 2 - 170, this.height / 2 - 20, 340, 20,
                VersionedText.literal("")
        );
        this.textField.setMaxLength(64); // default is 30 which is too little

        this.backButton = buttonWidget(this.width / 2 - 155, this.height / 2 + 50, 150, 20,
                VersionedText.translatable("automodpack.back"),
                button -> {
                    this.client.setScreen(parent);
                    if (!this.validated) {
                        this.canceledCallback.run();
                    }
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
            if (this.client != null) {
                this.client.setScreen(parent);
            }
            validatedCallback.run();
        } else {
            GlobalVariables.LOGGER.error("Server fingerprint validation failed, try again");
            if (this.client != null) {
                this.client.getToastManager().add(failedToast);
            }
        }
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.validation.text").formatted(Formatting.BOLD),
                this.width / 2, this.height / 2 - 100, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.validation.description"),
                this.width / 2, this.height / 2 - 75, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.validation.secDescription"),
                this.width / 2, this.height / 2 - 65, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.validation.thiDescription"),
                this.width / 2, this.height / 2 - 55, 16777215);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}