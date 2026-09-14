package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.VersionedToasts;
import pl.skidam.automodpack.client.ui.widget.Countdown;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

public class SkipVerificationScreen extends VersionedScreen {
	private static final int BODY = 420;
	private final Screen verificationScreen;
	private final Runnable validatedCallback;
	private final Toast failedToast = new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE,
			VersionedText.text("automodpack.validation.skip.failed"),
			VersionedText.text("automodpack.retry"));
	private static final String REQUIRED_TEXT = "I accept the risk";
	private static final int TIMER_SECONDS = 10;
	private final Countdown countdown = new Countdown(TIMER_SECONDS);
	private EditBox textField;
	private AbstractWidget confirmButton;
	private int fieldY;
	private List<MutableComponent> stackLines = List.of();
	private int stackTop;
	private int titleTop;

	public SkipVerificationScreen(Screen verificationScreen, Runnable validatedCallback) {
		super(VersionedText.text("automodpack.validation.skip.title"));
		this.verificationScreen = verificationScreen;
		this.validatedCallback = validatedCallback;
	}

	@Override
	protected void init() {
		super.init();
		initWidgets();
		this.setInitialFocus(this.textField);
	}

	private void initWidgets() {
		assert this.minecraft != null;
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> prose = new ArrayList<>();
		prose.addAll(wrapParagraph(this.font, VersionedText.text("automodpack.validation.skip.warning1").getString(), wrapWidth));
		prose.add(blankLine());
		prose.addAll(wrapParagraph(this.font, VersionedText.text("automodpack.validation.skip.warning2").getString(), wrapWidth, ChatFormatting.RED));
		prose.add(blankLine());
		prose.addAll(wrapParagraph(this.font, VersionedText.text("automodpack.validation.identity.publiclyTrusted").getString(), wrapWidth, ChatFormatting.GRAY));
		prose.add(blankLine());
		prose.addAll(wrapParagraph(this.font, VersionedText.text("automodpack.validation.skip.instruction").getString(), wrapWidth));
		// The typed phrase pins with the field that must receive it: confirm label, phrase, field, countdown hint.
		List<MutableComponent> stack = new ArrayList<>(wrapParagraph(this.font, VersionedText.text("automodpack.validation.skip.confirm.text").getString(), wrapWidth, ChatFormatting.GRAY));
		stack.add(VersionedText.literal("\"" + REQUIRED_TEXT + "\"").withStyle(ChatFormatting.ITALIC));
		stackLines = List.copyOf(stack);

		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.text("automodpack.back"), button -> ScreenImpl.setScreen(verificationScreen)),
				primaryAction(VersionedText.text("automodpack.skip"), button -> confirmSkip()));
		int stackHeight = stackLines.size() * LINE_HEIGHT + ActionAreaLayout.SEAM + ActionAreaLayout.BUTTON_HEIGHT + ActionAreaLayout.SEAM + LINE_HEIGHT;
		DialogLayout layout = layoutDialogWithActions(28, LINE_HEIGHT, prose.size() * LINE_HEIGHT, stackHeight, footer);
		this.titleTop = layout.titleTop();
		List<AbstractWidget> buttons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		this.confirmButton = buttons.get(1);
		this.confirmButton.active = false;
		DialogColumn column = layout.column();
		this.addCenteredScrollBody(BODY, column.bodyTop(), column.bodyBottom(), prose);
		stackTop = column.stackTop();
		fieldY = stackTop + stackLines.size() * LINE_HEIGHT + ActionAreaLayout.SEAM;

		int fieldLeft = panelLeft(BODY);
		this.textField = fieldWidget(fieldLeft, fieldY, panelWidth(BODY), VersionedText.literal(REQUIRED_TEXT), VersionedText.text("automodpack.learnmore"), 128);
	}

	private void confirmSkip() {
		String input = textField.getValue().strip();

		if (input.equals(REQUIRED_TEXT)) {
			confirmButton.active = false;
			ScreenManager.waiting();
			validatedCallback.run();
		} else {
			Constants.LOGGER.error("Skip verification text mismatch, try again");
			VersionedToasts.add(failedToast);
		}
	}

	@Override
	public void tick() {
		super.tick();
		countdown.tick();
		if (!countdown.running()) confirmButton.active = true;
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.validation.skip.title").withStyle(ChatFormatting.BOLD), this.width / 2, titleTop, TextColors.LIGHT_RED);
		drawCenteredLines(matrices, stackLines, stackTop);
		if (countdown.running())
			drawCountdown(matrices, VersionedText.text("automodpack.validation.skip.countdown", countdown.secondsRemaining()), fieldY + ActionAreaLayout.BUTTON_HEIGHT + ActionAreaLayout.SEAM);
	}

	@Override
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		if (textField.isFocused() && isEnterKey(keyCode)) {
			if (confirmButton.active) {
				confirmSkip();
				return true;
			}
		}
		return super.onKeyPress(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(verificationScreen));
	}
}
