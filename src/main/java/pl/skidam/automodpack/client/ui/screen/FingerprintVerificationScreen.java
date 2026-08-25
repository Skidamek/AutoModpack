package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.TextColors;

import java.util.ArrayList;
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
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class FingerprintVerificationScreen extends VersionedScreen {
	private final Screen parent;
	private final String serverFingerprint;
	private final String originFull;
	private final Runnable validatedCallback;
	private final Runnable canceledCallback;
	private boolean validated = false;
	private String inputText = "";
	private final Toast failedToast = new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE,
			VersionedText.translatable("automodpack.validation.failed"),
			VersionedText.translatable("automodpack.retry"));
	private EditBox textField;
	private Button verifyButton;

	public FingerprintVerificationScreen(Screen parent, String serverFingerprint, String origin, Runnable validatedCallback, Runnable canceledCallback) {
		super(VersionedText.translatable("automodpack.validation.title"));
		this.parent = parent;
		this.serverFingerprint = serverFingerprint;
		this.originFull = origin == null ? "" : origin;
		this.validatedCallback = validatedCallback;
		this.canceledCallback = canceledCallback;
	}

	@Override
	protected void init() {
		super.init();
		initWidgets();
		if (!inputText.isEmpty()) this.textField.setValue(inputText);
		this.addRenderableWidget(this.textField);
		this.setInitialFocus(this.textField);
	}

	private void initWidgets() {
		assert this.minecraft != null;
		int fieldLeft = panelLeft(ActionAreaLayout.FOOTER_RAIL);
		int fieldWidth = Math.max(1, panelWidth(ActionAreaLayout.FOOTER_RAIL) - 24);
		int fieldY = this.height / 2 + 20;
		this.textField = new EditBox(this.font, fieldLeft, fieldY, fieldWidth, 20, VersionedText.literal(""));
		this.textField.setMaxLength(64);

		List<Button> buttons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> {
					ScreenImpl.setScreen(parent);
					if (!this.validated) this.canceledCallback.run();
				}),
				optionalAction(VersionedText.translatable("automodpack.skip"), button -> ScreenImpl.setScreen(new SkipVerificationScreen(this, this.validatedCallback))),
				primaryAction(VersionedText.translatable("automodpack.validation.verify"), button -> verifyFingerprint())));
		this.verifyButton = buttons.get(2);

		Button wikiButton = iconButtonWidget(fieldLeft + fieldWidth + 4, fieldY, 20, 16,
				button -> Util.getPlatform().openUri("https://moddedmc.wiki/en/project/automodpack/latest/docs/technicals/certificate"),
				"link", VersionedText.translatable("automodpack.learnmore"));
		this.addRenderableWidget(wikiButton);
		setTooltip(wikiButton, VersionedText.translatable("automodpack.learnmore"));

		String originLabel = truncateToWidth(this.font, originFull, Math.max(1, panelWidth(ActionAreaLayout.FOOTER_RAIL) - 8));
		int originWidth = Math.max(1, this.font.width(originLabel));
		if (originLabel.equals(originFull) || originFull.isBlank()) return;
		Button originHit = buttonWidget(this.width / 2 - originWidth / 2, this.height / 2 - 82, originWidth, 12, VersionedText.literal(""), button -> {});
		this.addRenderableWidget(originHit);
		setTooltip(originHit, VersionedText.literal(originFull));
	}

	private void forceValidate() {
		verifyButton.active = false;
		this.validated = true;
		this.inputText = "";
		ScreenManager.waiting();
		validatedCallback.run();
	}

	private void verifyFingerprint() {
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
		int wrapWidth = Math.max(1, panelWidth(ActionAreaLayout.FOOTER_RAIL) - 8);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.validation.title").withStyle(ChatFormatting.BOLD), this.width / 2, this.height / 2 - 95, TextColors.WHITE);

		String originLabel = truncateToWidth(this.font, originFull, wrapWidth);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(originLabel).withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD), this.width / 2, this.height / 2 - 80, TextColors.WHITE);

		List<String> body = new ArrayList<>();
		body.addAll(wrapToWidth(this.font, VersionedText.translatable("automodpack.validation.identity.purpose").getString(), wrapWidth, 2));
		body.addAll(wrapToWidth(this.font, VersionedText.translatable("automodpack.validation.identity.paste").getString(), wrapWidth, 3));
		body.addAll(wrapToWidth(this.font, VersionedText.translatable("automodpack.validation.identity.notPack").getString(), wrapWidth, 2));
		int y = this.height / 2 - 65;
		for (String line : body) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line), this.width / 2, y, TextColors.WHITE);
			y += lineHeight;
		}

		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.validation.fingerprint.label"), this.width / 2, this.height / 2 - 5, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(getConcatenatedFingerprint()), this.width / 2, this.height / 2 - 5 + lineHeight, TextColors.LIGHT_GRAY);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.validation.confirm.text"), this.width / 2, this.height / 2 + 8, TextColors.WHITE);

		List<String> underField = wrapToWidth(this.font, VersionedText.translatable("automodpack.validation.identity.methodHint").getString(), wrapWidth, 2);
		int underY = this.height / 2 + 42;
		for (String line : underField) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.GRAY), this.width / 2, underY, TextColors.WHITE);
			underY += lineHeight;
		}
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
