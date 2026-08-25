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
import net.minecraft.network.chat.MutableComponent;
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.TextScrollWidget;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class FingerprintVerificationScreen extends VersionedScreen {
	private static final int BODY = 320;
	private static final int LINE = TextScrollWidget.ROW_HEIGHT;
	private static final int HELP_SIZE = 20;
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
	private String originDisplay = "";
	private int fieldY;
	private List<String> hintLines = List.of();

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
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		originDisplay = truncateToWidth(this.font, PackConfirmCopy.displayOrigin(originFull), wrapWidth);
		List<MutableComponent> before = new ArrayList<>();
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.identity.purpose").getString(), wrapWidth));
		before.add(blankLine());
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.identity.paste").getString(), wrapWidth));
		before.add(blankLine());
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.identity.notPack").getString(), wrapWidth));
		before.add(blankLine());
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.fingerprint.label").getString(), wrapWidth, ChatFormatting.GRAY));
		before.add(VersionedText.literal(getConcatenatedFingerprint()).withStyle(ChatFormatting.GRAY));
		before.add(blankLine());
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.confirm.text").getString(), wrapWidth, ChatFormatting.GRAY));
		hintLines = wrapToWidth(this.font, VersionedText.translatable("automodpack.validation.identity.methodHint").getString(), wrapWidth);

		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> {
					ScreenImpl.setScreen(parent);
					if (!this.validated) this.canceledCallback.run();
				}),
				optionalAction(VersionedText.translatable("automodpack.skip"), button -> ScreenImpl.setScreen(new SkipVerificationScreen(this, this.validatedCallback))),
				primaryAction(VersionedText.translatable("automodpack.validation.verify"), button -> verifyFingerprint()));
		List<Button> buttons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		this.verifyButton = buttons.get(2);
		int footerTop = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		int hintHeight = Math.max(LINE, hintLines.size() * LINE);
		int pinned = ActionAreaLayout.BUTTON_HEIGHT + ActionAreaLayout.SEAM + hintHeight;
		int bodyTop = 42;
		int listBottom = Math.min(bodyTop + before.size() * LINE, footerTop - 4 - pinned - ActionAreaLayout.GAP);
		this.addCenteredScrollBody(BODY, bodyTop, Math.max(bodyTop + LINE, listBottom), before);
		fieldY = Math.max(bodyTop + LINE, listBottom) + ActionAreaLayout.SEAM;

		int fieldLeft = panelLeft(BODY);
		int fieldWidth = Math.max(1, panelWidth(BODY) - ActionAreaLayout.SEAM - HELP_SIZE);
		this.textField = new EditBox(this.font, fieldLeft, fieldY, fieldWidth, ActionAreaLayout.BUTTON_HEIGHT, VersionedText.literal(""));
		this.textField.setMaxLength(64);

		Button help = buttonWidget(fieldLeft + fieldWidth + ActionAreaLayout.SEAM, fieldY, HELP_SIZE, HELP_SIZE, VersionedText.literal("?"),
				button -> Util.getPlatform().openUri("https://moddedmc.wiki/en/project/automodpack/latest/docs/technicals/certificate"));
		this.addRenderableWidget(help);
		setTooltip(help, VersionedText.translatable("automodpack.learnmore"));
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.validation.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(originDisplay).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), this.width / 2, 28, TextColors.WHITE);
		int underY = fieldY + ActionAreaLayout.BUTTON_HEIGHT + ActionAreaLayout.SEAM;
		for (String line : hintLines) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.GRAY), this.width / 2, underY, TextColors.WHITE);
			underY += LINE;
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
