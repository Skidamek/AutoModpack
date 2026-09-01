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
import pl.skidam.automodpack.client.ui.widget.TextScrollWidget;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class SkipVerificationScreen extends VersionedScreen {
	private static final int BODY = 420;
	private static final int LINE = TextScrollWidget.ROW_HEIGHT;
	private final Screen verificationScreen;
	private final Runnable validatedCallback;
	private final Toast failedToast = new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE,
			VersionedText.translatable("automodpack.validation.skip.failed"),
			VersionedText.translatable("automodpack.retry"));
	private static final String REQUIRED_TEXT = "I accept the risk";
	private static final int TIMER_SECONDS = 10;
	private EditBox textField;
	private Button confirmButton;
	private int ticksRemaining;
	private int fieldY;
	private int bodyTop = 42;
	private List<MutableComponent> bodyLines = List.of();

	public SkipVerificationScreen(Screen verificationScreen, Runnable validatedCallback) {
		super(VersionedText.translatable("automodpack.validation.skip.title"));
		this.verificationScreen = verificationScreen;
		this.validatedCallback = validatedCallback;
		this.ticksRemaining = TIMER_SECONDS * 20;
	}

	@Override
	protected void init() {
		super.init();
		initWidgets();
		this.addRenderableWidget(this.textField);
		this.setInitialFocus(this.textField);
	}

	private void initWidgets() {
		assert this.minecraft != null;
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> before = new ArrayList<>();
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.skip.warning1").getString(), wrapWidth));
		before.add(blankLine());
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.skip.warning2").getString(), wrapWidth, ChatFormatting.RED));
		before.add(blankLine());
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.identity.publiclyTrusted").getString(), wrapWidth, ChatFormatting.GRAY));
		before.add(blankLine());
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.skip.instruction").getString(), wrapWidth));
		before.add(blankLine());
		before.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.validation.skip.confirm.text").getString(), wrapWidth, ChatFormatting.GRAY));
		before.add(VersionedText.literal("\"" + REQUIRED_TEXT + "\"").withStyle(ChatFormatting.ITALIC));

		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> ScreenImpl.setScreen(verificationScreen)),
				primaryAction(VersionedText.translatable("automodpack.skip"), button -> confirmSkip()));
		List<Button> buttons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		this.confirmButton = buttons.get(1);
		this.confirmButton.active = false;
		int footerTop = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, footer);
		int hintHeight = LINE;
		int pinned = ActionAreaLayout.BUTTON_HEIGHT + ActionAreaLayout.SEAM + hintHeight;
		bodyTop = 42;
		int neededText = Math.max(LINE, before.size() * LINE);
		int availableText = Math.max(LINE, footerTop - 4 - pinned - ActionAreaLayout.GAP - bodyTop);
		if (neededText <= availableText) {
			bodyLines = List.copyOf(before);
			fieldY = bodyTop + neededText + ActionAreaLayout.SEAM;
		} else {
			bodyLines = List.of();
			this.addCenteredScrollBody(BODY, bodyTop, footerTop - 4 - pinned - ActionAreaLayout.GAP, before);
			fieldY = footerTop - 4 - pinned;
		}

		int fieldLeft = panelLeft(BODY);
		this.textField = fieldWidget(fieldLeft, fieldY, panelWidth(BODY), VersionedText.literal(REQUIRED_TEXT), VersionedText.translatable("automodpack.learnmore"), 128);
	}

	private void confirmSkip() {
		String input = textField.getValue().strip();

		if (input.equals(REQUIRED_TEXT)) {
			confirmButton.active = false;
			ScreenManager.waiting();
			validatedCallback.run();
		} else {
			Constants.LOGGER.error("Skip verification text mismatch, try again");
			if (this.minecraft != null) {
				/*? if > 1.21.1 {*/
				this.minecraft.gui.toastManager().addToast(failedToast);
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
			if (ticksRemaining == 0) confirmButton.active = true;
		}
	}

	private int getRemainingSeconds() {
		return (ticksRemaining + 19) / 20;
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.validation.skip.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.LIGHT_RED);
		int y = bodyTop;
		for (MutableComponent line : bodyLines) {
			drawCenteredTextWithShadow(matrices, this.font, line, this.width / 2, y, TextColors.WHITE);
			y += LINE;
		}
		if (ticksRemaining > 0)
			drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.translatable("automodpack.validation.skip.countdown", getRemainingSeconds()).withStyle(ChatFormatting.GRAY), this.width / 2,
					fieldY + ActionAreaLayout.BUTTON_HEIGHT + ActionAreaLayout.SEAM, TextColors.WHITE);
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
