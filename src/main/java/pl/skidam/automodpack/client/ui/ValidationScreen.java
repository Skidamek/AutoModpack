package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.screens.Screen;
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
    private final Toast failedToast = new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE, VersionedText.translatable("automodpack.validation.failed"), VersionedText.translatable("automodpack.retry"));
    private EditBox textField;
    private Button backButton;
    private Button validateButton;

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

        this.addRenderableWidget(this.textField);
        this.addRenderableWidget(this.backButton);
        this.addRenderableWidget(this.validateButton);
        this.setInitialFocus(this.textField);
    }

    public void initWidgets() {
        assert this.minecraft != null;
        this.textField = new EditBox(this.font, this.width / 2 - 170, this.height / 2 - 20, 340, 20,
                VersionedText.literal("")
        );
        this.textField.setMaxLength(64); // default is 30 which is too little

        this.backButton = buttonWidget(this.width / 2 - 155, this.height / 2 + 50, 150, 20,
                VersionedText.translatable("automodpack.back"),
                button -> {
                    this.minecraft.setScreen(parent);
                    if (!this.validated) {
                        this.canceledCallback.run();
                    }
                }
        );

        this.validateButton = buttonWidget(this.width / 2 + 5, this.height / 2 + 50, 150, 20,
                VersionedText.translatable("automodpack.validation.run"),
                button -> validate(textField.getValue()));
    }

    private void validate(String input) {
        input = input.strip();
        if (input.equals(serverFingerprint) || input.equals("I AM INCREDIBLY STUPID")) {
            validateButton.active = false;
            this.validated = true;
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
            validatedCallback.run();
        } else {
            GlobalVariables.LOGGER.error("Server fingerprint validation failed, try again");
            if (this.minecraft != null) {
                this.minecraft.getToasts().addToast(failedToast);
            }
        }
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.validation.text").withStyle(ChatFormatting.BOLD),
                this.width / 2, this.height / 2 - 100, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.validation.description"),
                this.width / 2, this.height / 2 - 75, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.validation.secDescription"),
                this.width / 2, this.height / 2 - 65, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.validation.thiDescription"),
                this.width / 2, this.height / 2 - 55, TextColors.WHITE);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}