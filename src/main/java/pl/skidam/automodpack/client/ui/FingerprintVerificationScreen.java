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

public class FingerprintVerificationScreen extends VersionedScreen {
    private final Screen parent;
    private final String serverFingerprint;
    private final Runnable validatedCallback;
    private final Runnable canceledCallback;
    private boolean validated = false;
    private final Toast failedToast = new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE, 
            VersionedText.translatable("automodpack.validation.failed"), 
            VersionedText.translatable("automodpack.retry"));
    private EditBox textField;
    private Button backButton;
    private Button verifyButton;
    private Button skipButton;

    public FingerprintVerificationScreen(Screen parent, String serverFingerprint, Runnable validatedCallback,
                            Runnable canceledCallback) {
        super(VersionedText.literal("FingerprintVerificationScreen"));
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
        this.addRenderableWidget(this.verifyButton);
        this.addRenderableWidget(this.skipButton);
        this.setInitialFocus(this.textField);
    }

    public void initWidgets() {
        assert this.minecraft != null;
        
        // Text field for fingerprint input
        this.textField = new EditBox(this.font, this.width / 2 - 150, this.height / 2 + 15, 300, 20,
                VersionedText.literal("")
        );
        this.textField.setMaxLength(64);
        /*? if >= 1.19.4 {*/
        this.textField.setHint(VersionedText.translatable("automodpack.validation.fingerprint.hint"));
        /*?}*/

        // Back button
        this.backButton = buttonWidget(this.width / 2 - 155, this.height / 2 + 80, 100, 20,
                VersionedText.translatable("automodpack.back"),
                button -> {
                    this.minecraft.setScreen(parent);
                    if (!this.validated) {
                        this.canceledCallback.run();
                    }
                }
        );

        // Verify button
        this.verifyButton = buttonWidget(this.width / 2 - 50, this.height / 2 + 80, 100, 20,
                VersionedText.translatable("automodpack.validation.verify"),
                button -> verifyFingerprint());

        // Skip verification button
        this.skipButton = buttonWidget(this.width / 2 + 55, this.height / 2 + 80, 100, 20,
                VersionedText.translatable("automodpack.validation.skip"),
                button -> {
                    assert this.minecraft != null;
                    this.minecraft.setScreen(new SkipVerificationScreen(this, this.parent, 
                            this.validatedCallback, this.canceledCallback));
                });
    }

    private void verifyFingerprint() {
        String input = textField.getValue().strip();
        
        if (input.equals(serverFingerprint)) {
            verifyButton.active = false;
            this.validated = true;
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
            validatedCallback.run();
        } else {
            GlobalVariables.LOGGER.error("Server fingerprint validation failed, try again");
            if (this.minecraft != null) {
                /*? if > 1.21.1 {*/
                this.minecraft.getToastManager().addToast(failedToast);
                /*?} else {*/
                /*this.minecraft.getToasts().addToast(failedToast);
                *//*?}*/
            }
        }
    }

    private String getConcatenatedFingerprint() {
        // Concatenate fingerprint to fit on screen (first 16 chars + "..." + last 16 chars)
        if (serverFingerprint.length() <= 35) {
            return serverFingerprint;
        }
        return serverFingerprint.substring(0, 16) + "..." + serverFingerprint.substring(serverFingerprint.length() - 16);
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        // Title
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.title").withStyle(ChatFormatting.BOLD),
                this.width / 2, this.height / 2 - 95, TextColors.WHITE);

        // Description
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.description"),
                this.width / 2, this.height / 2 - 75, TextColors.WHITE);

        // Server fingerprint label
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.fingerprint.label"),
                this.width / 2, this.height / 2 - 55, TextColors.LIGHT_GRAY);

        // Server fingerprint value (concatenated, bold)
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.literal(getConcatenatedFingerprint()).withStyle(ChatFormatting.BOLD),
                this.width / 2, this.height / 2 - 43, TextColors.WHITE);

        // Instructions
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.instruction"),
                this.width / 2, this.height / 2 - 15, TextColors.LIGHT_GRAY);

        // Confirmation text
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.confirm.text"),
                this.width / 2, this.height / 2, TextColors.LIGHT_GRAY);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
