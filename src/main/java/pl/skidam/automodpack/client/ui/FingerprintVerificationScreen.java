package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
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
    private Button wikiButton;

    public FingerprintVerificationScreen(Screen parent, String serverFingerprint, Runnable validatedCallback, Runnable canceledCallback) {
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
        this.addRenderableWidget(this.wikiButton);
        this.setInitialFocus(this.textField);
    }

    public void initWidgets() {
        assert this.minecraft != null;
        
        // Text field for fingerprint input (same size as skip screen)
        this.textField = new EditBox(this.font, this.width / 2 - 170, this.height / 2 + 15, 340, 20,
                VersionedText.literal("")
        );
        this.textField.setMaxLength(64);

        // Skip button (left, indicates misc action)
        this.skipButton = buttonWidget(this.width / 2 - 155, this.height / 2 + 80, 100, 20,
                VersionedText.translatable("automodpack.skip"),
                button -> {
                    assert this.minecraft != null;
                    this.minecraft.setScreen(new SkipVerificationScreen(this, this.parent, this.validatedCallback));
                });

        // Verify button (middle - primary action, bold, confirmation)
        this.verifyButton = buttonWidget(this.width / 2 - 50, this.height / 2 + 80, 100, 20,
                VersionedText.translatable("automodpack.validation.verify").withStyle(ChatFormatting.BOLD),
                button -> verifyFingerprint());

        // Back button (right, like everywhere else in minecraft)
        this.backButton = buttonWidget(this.width / 2 + 55, this.height / 2 + 80, 100, 20,
                VersionedText.translatable("automodpack.back"),
                button -> {
                    this.minecraft.setScreen(parent);
                    if (!this.validated) {
                        this.canceledCallback.run();
                    }
                }
        );
        // Wiki button (icon button aligned to the right of text field)
        this.wikiButton = iconButtonWidget(this.width / 2 + 22 + 150, this.height / 2 + 15, 20, 16,
                button -> Util.getPlatform().openUri("https://moddedmc.wiki/en/project/automodpack/latest/docs/technicals/certificate"),
                "link");

        setTooltip(wikiButton, VersionedText.translatable("automodpack.learnmore"));
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
        int lineHeight = 12; // Consistent line spacing
        
        // Title
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.title").withStyle(ChatFormatting.BOLD),
                this.width / 2, this.height / 2 - 85, TextColors.WHITE);

        // Description line 1
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.description1"),
                this.width / 2, this.height / 2 - 65, TextColors.WHITE);

        // Description line 2
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.description2"),
                this.width / 2, this.height / 2 - 65 + lineHeight, TextColors.WHITE);

        // Server fingerprint label
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.fingerprint.label"),
                this.width / 2, this.height / 2 - 35, TextColors.WHITE);

        // Server fingerprint value (concatenated, gray, not bold - intentionally harder to read)
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.literal(getConcatenatedFingerprint()),
                this.width / 2, this.height / 2 - 35 + lineHeight, TextColors.LIGHT_GRAY);

        // Confirmation text
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.confirm.text"),
                this.width / 2, this.height / 2 - 15 + lineHeight, TextColors.WHITE);
    }

    @Override
    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        if (textField.isFocused() && keyCode == 257) { // Enter key (GLFW_KEY_ENTER = 257)
            if (verifyButton.active) {
                verifyFingerprint();
                return true;
            }
        }
        return super.onKeyPress(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
