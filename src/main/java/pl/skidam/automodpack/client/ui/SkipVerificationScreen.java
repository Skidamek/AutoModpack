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

public class SkipVerificationScreen extends VersionedScreen {
    private final Screen verificationScreen;
    private final Screen parent;
    private final Runnable validatedCallback;
    private final Runnable canceledCallback;
    private boolean validated = false;
    private final Toast failedToast = new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE, 
            VersionedText.translatable("automodpack.validation.skip.failed"), 
            VersionedText.translatable("automodpack.retry"));
    private static final String REQUIRED_TEXT = "I accept the risk";
    private static final int TIMER_SECONDS = 10;
    private EditBox textField;
    private Button backButton;
    private Button confirmButton;
    private int ticksRemaining;

    public SkipVerificationScreen(Screen verificationScreen, Screen parent, Runnable validatedCallback,
                            Runnable canceledCallback) {
        super(VersionedText.literal("SkipVerificationScreen"));
        this.verificationScreen = verificationScreen;
        this.parent = parent;
        this.validatedCallback = validatedCallback;
        this.canceledCallback = canceledCallback;
        this.ticksRemaining = TIMER_SECONDS * 20; // 20 ticks per second
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addRenderableWidget(this.textField);
        this.addRenderableWidget(this.backButton);
        this.addRenderableWidget(this.confirmButton);
        this.setInitialFocus(this.textField);
    }

    public void initWidgets() {
        assert this.minecraft != null;
        
        // Text field for risk acceptance
        this.textField = new EditBox(this.font, this.width / 2 - 170, this.height / 2 + 15, 340, 20,
                VersionedText.literal("")
        );
        this.textField.setMaxLength(128);

        // Back button (returns to fingerprint verification screen) - same Y position as verification screen
        this.backButton = buttonWidget(this.width / 2 - 155, this.height / 2 + 80, 150, 20,
                VersionedText.translatable("automodpack.back"),
                button -> {
                    assert this.minecraft != null;
                    this.minecraft.setScreen(verificationScreen);
                }
        );

        // Confirm skip button (initially disabled, unlocks after timer) - same Y position as verification screen
        // Button text will be updated dynamically in tick()
        this.confirmButton = buttonWidget(this.width / 2 + 5, this.height / 2 + 80, 150, 20,
                VersionedText.translatable("automodpack.validation.skip.confirm"),
                button -> confirmSkip());
        this.confirmButton.active = false;
        updateButtonText();
    }
    
    private void updateButtonText() {
        if (ticksRemaining > 0) {
            int seconds = getRemainingSeconds();
            this.confirmButton.setMessage(
                VersionedText.translatable("automodpack.validation.skip.confirm")
                    .append(VersionedText.literal(" (" + seconds + "s)"))
            );
        } else {
            this.confirmButton.setMessage(
                VersionedText.translatable("automodpack.validation.skip.confirm")
            );
        }
    }

    private void confirmSkip() {
        String input = textField.getValue().strip();
        
        if (input.equals(REQUIRED_TEXT)) {
            confirmButton.active = false;
            this.validated = true;
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
            validatedCallback.run();
        } else {
            GlobalVariables.LOGGER.error("Skip verification text mismatch, try again");
            if (this.minecraft != null) {
                /*? if > 1.21.1 {*/
                this.minecraft.getToastManager().addToast(failedToast);
                /*?} else {*/
                /*this.minecraft.getToasts().addToast(failedToast);
                *//*?}*/
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (ticksRemaining > 0) {
            ticksRemaining--;
            updateButtonText();
            if (ticksRemaining == 0) {
                confirmButton.active = true;
            }
        }
    }

    private int getRemainingSeconds() {
        return (ticksRemaining + 19) / 20; // Round up
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        int lineHeight = 12; // Consistent line spacing
        
        // Warning title
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.skip.title").withStyle(ChatFormatting.BOLD),
                this.width / 2, this.height / 2 - 85, TextColors.LIGHT_RED);

        // Warning message line 1
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.skip.warning1"),
                this.width / 2, this.height / 2 - 65, TextColors.WHITE);

        // Warning message line 2
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.skip.warning2"),
                this.width / 2, this.height / 2 - 65 + lineHeight, TextColors.LIGHT_RED);

        // Instructions
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.skip.instruction"),
                this.width / 2, this.height / 2 - 35, TextColors.WHITE);

        // Required text to type (displayed prominently)
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.literal("\"" + REQUIRED_TEXT + "\"").withStyle(ChatFormatting.ITALIC),
                this.width / 2, this.height / 2 - 10, TextColors.WHITE);

        // Confirmation prompt
        drawCenteredTextWithShadow(matrices, this.font, 
                VersionedText.translatable("automodpack.validation.skip.confirm.text"),
                this.width / 2, this.height / 2 + 3, TextColors.WHITE);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
