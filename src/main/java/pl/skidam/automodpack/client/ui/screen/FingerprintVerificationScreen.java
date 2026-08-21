package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.TextColors;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.screens.Screen;
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class FingerprintVerificationScreen extends VersionedScreen {
	private final Screen parent;
	private final String serverFingerprint;
	private final Runnable validatedCallback;
	private final Runnable canceledCallback;
	private boolean validated = false;
	private String inputText = "";
	private final Toast failedToast = new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE,
			VersionedText.translatable("automodpack.validation.failed"),
			VersionedText.translatable("automodpack.retry"));
	private EditBox textField;
	private Button backButton;
	private Button verifyButton;
	private Button skipButton;
	private Button wikiButton;

	public FingerprintVerificationScreen(Screen parent, String serverFingerprint, Runnable validatedCallback, Runnable canceledCallback) {
		super(VersionedText.translatable("automodpack.validation.title"));
		this.parent = parent;
		this.serverFingerprint = serverFingerprint;
		this.validatedCallback = validatedCallback;
		this.canceledCallback = canceledCallback;
	}

	@Override
	protected void init() {
		super.init();

		initWidgets();
		if (!inputText.isEmpty()) {
			this.textField.setValue(inputText);
		}

		this.addRenderableWidget(this.textField);
		this.addRenderableWidget(this.backButton);
		this.addRenderableWidget(this.verifyButton);
		this.addRenderableWidget(this.skipButton);
		this.addRenderableWidget(this.wikiButton);
		this.setInitialFocus(this.textField);
	}

	public void initWidgets() {
		assert this.minecraft != null;

		int fieldLeft = panelLeft(340);
		int fieldWidth = Math.max(1, panelWidth(340) - 24);
		this.textField = new EditBox(this.font, fieldLeft, this.height / 2 + 27, fieldWidth, 20,
				VersionedText.literal("")
		);
		this.textField.setMaxLength(64);

		List<Button> buttons = addActionArea(340, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> {
					ScreenImpl.setScreen(parent);
					if (!this.validated) this.canceledCallback.run();
				}),
				optionalAction(VersionedText.translatable("automodpack.skip"), button -> ScreenImpl.setScreen(new SkipVerificationScreen(this, this.validatedCallback))),
				primaryAction(VersionedText.translatable("automodpack.validation.verify").withStyle(ChatFormatting.BOLD), button -> verifyFingerprint())));
		this.backButton = buttons.get(0);
		this.skipButton = buttons.get(1);
		this.verifyButton = buttons.get(2);

		this.wikiButton = iconButtonWidget(fieldLeft + fieldWidth + 4, this.height / 2 + 29, 20, 16,
				button -> Util.getPlatform().openUri("https://moddedmc.wiki/en/project/automodpack/latest/docs/technicals/certificate"),
				"link", VersionedText.translatable("automodpack.learnmore"));

		setTooltip(wikiButton, VersionedText.translatable("automodpack.learnmore"));
	}

	public void forceValidate() {
		verifyButton.active = false;
		this.validated = true;
		this.inputText = "";
		ScreenManager.waiting();
		validatedCallback.run();
	}

	public void setInputText(String text) {
		this.inputText = text;
		if (this.textField != null) {
			this.textField.setValue(text);
		}
	}

	public void verifyFingerprint() {
		String input = textField.getValue().strip();
		inputText = input;
		if (input.equals(serverFingerprint)) {
			forceValidate();
		} else {
			Constants.LOGGER.error("Server fingerprint validation failed, try again");
			if (this.minecraft != null) {
				/*? if > 1.21.1 {*/
				this.minecraft.gui.toastManager().addToast(failedToast);
				/*?} else {*/
				/*this.minecraft.getToasts().addToast(failedToast);
				*//*?}*/
			}
		}
	}

	private String getConcatenatedFingerprint() {
		return NetUtils.shortenFingerprint(serverFingerprint, 16);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int lineHeight = 12;

		// Title
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.translatable("automodpack.validation.title").withStyle(ChatFormatting.BOLD),
				this.width / 2, this.height / 2 - 85, TextColors.WHITE);

		String description = VersionedText.translatable("automodpack.validation.description1").getString() + " "
				+ VersionedText.translatable("automodpack.validation.description2").getString();
		List<String> descriptionLines = wrapToWidth(this.font, description, this.width - 24, 3);
		for (int index = 0; index < descriptionLines.size(); index++)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(descriptionLines.get(index)), this.width / 2, this.height / 2 - 65 + index * lineHeight, TextColors.WHITE);

		// Server fingerprint label
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.translatable("automodpack.validation.fingerprint.label"),
				this.width / 2, this.height / 2 - 23, TextColors.WHITE);

		// Server fingerprint value (concatenated, gray, not bold - intentionally harder to read)
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.literal(getConcatenatedFingerprint()),
				this.width / 2, this.height / 2 - 23 + lineHeight, TextColors.LIGHT_GRAY);

		// Confirmation text
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.translatable("automodpack.validation.confirm.text"),
				this.width / 2, this.height / 2 + 9, TextColors.WHITE);
	}

	@Override
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		if (textField.isFocused() && isEnterKey(keyCode)) {
			if (verifyButton.active) {
				verifyFingerprint();
				return true;
			}
		}
		return super.onKeyPress(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> {
			ScreenImpl.setScreen(parent);
			if (!validated) canceledCallback.run();
		});
	}
}
