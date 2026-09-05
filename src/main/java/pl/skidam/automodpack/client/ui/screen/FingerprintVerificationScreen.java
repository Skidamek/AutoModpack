package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.TextColors;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
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
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class FingerprintVerificationScreen extends VersionedScreen {
	private static final int BODY = 420;
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
	private List<MutableComponent> stackLines = List.of();
	private List<MutableComponent> hintLines = List.of();
	private int stackTop;

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
		List<MutableComponent> prose = new ArrayList<>();
		prose.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.identity.purpose").getString(), wrapWidth));
		prose.add(blankLine());
		prose.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.identity.paste").getString(), wrapWidth));
		prose.add(blankLine());
		prose.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.identity.notPack").getString(), wrapWidth));
		prose.add(blankLine());
		prose.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.identity.publiclyTrusted").getString(), wrapWidth, ChatFormatting.GRAY));
		// The fingerprint pins with the field it feeds: paste instruction, caption, value, then the field and its method hint.
		List<MutableComponent> stack = new ArrayList<>(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.confirm.text").getString(), wrapWidth, ChatFormatting.GRAY));
		stack.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.fingerprint.label").getString(), wrapWidth, ChatFormatting.GRAY));
		stack.add(VersionedText.literal(getConcatenatedFingerprint()).withStyle(ChatFormatting.GRAY));
		stackLines = List.copyOf(stack);
		hintLines = wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.identity.methodHint").getString(), wrapWidth, ChatFormatting.GRAY);

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
		int stackHeight = stackLines.size() * LINE_HEIGHT + ActionAreaLayout.SEAM + ActionAreaLayout.BUTTON_HEIGHT + ActionAreaLayout.SEAM + hintLines.size() * LINE_HEIGHT;
		DialogColumn column = layoutDialogColumn(42, footerTop, prose.size() * LINE_HEIGHT, stackHeight);
		this.addCenteredScrollBody(BODY, column.bodyTop(), column.bodyBottom(), prose);
		stackTop = column.stackTop();
		fieldY = stackTop + stackLines.size() * LINE_HEIGHT + ActionAreaLayout.SEAM;

		int fieldLeft = panelLeft(BODY);
		this.textField = fieldWidget(fieldLeft, fieldY, panelWidth(BODY), VersionedText.translatable("automodpack.validation.fingerprint.field"), VersionedText.translatable("automodpack.learnmore"), 64);
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
		drawCenteredLines(matrices, stackLines, stackTop);
		drawCenteredLines(matrices, hintLines, fieldY + ActionAreaLayout.BUTTON_HEIGHT + ActionAreaLayout.SEAM);
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
