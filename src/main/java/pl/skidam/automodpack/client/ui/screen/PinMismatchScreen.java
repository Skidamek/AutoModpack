package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.TextScrollWidget;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/**
 * Security rejection for a pinned certificate that no longer matches. States what happened, what it can mean,
 * and what to do; it deliberately offers no way past the pin.
 */
public final class PinMismatchScreen extends VersionedScreen {
	private static final int BODY = 420;
	private static final int LINE = TextScrollWidget.ROW_HEIGHT;
	private final Screen parent;
	private final String origin;
	private final String expectedFingerprint;
	private final String presentedFingerprint;
	private List<MutableComponent> bodyLines = List.of();
	private boolean copied;

	public PinMismatchScreen(Screen parent, String origin, String expectedFingerprint, String presentedFingerprint) {
		super(VersionedText.translatable("automodpack.pinMismatch.title"));
		this.parent = parent;
		this.origin = origin;
		this.expectedFingerprint = expectedFingerprint;
		this.presentedFingerprint = presentedFingerprint;
	}

	@Override
	protected void init() {
		super.init();
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.pinMismatch.happened", origin).getString(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrappedFingerprint(VersionedText.translatable("automodpack.pinMismatch.pinned").getString(), expectedFingerprint, wrapWidth));
		lines.addAll(wrappedFingerprint(VersionedText.translatable("automodpack.pinMismatch.presented").getString(), presentedFingerprint, wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.pinMismatch.meaning").getString(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.pinMismatch.do").getString(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.pinMismatch.dont").getString(), wrapWidth, ChatFormatting.RED));
		bodyLines = List.copyOf(lines);
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28,
				actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.error.copyDetails"), button -> copyDetails())),
				actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), button -> ScreenImpl.setScreen(parent))));
	}

	private List<MutableComponent> wrappedFingerprint(String label, String fingerprint, int wrapWidth) {
		List<MutableComponent> lines = new ArrayList<>(wrapParagraph(this.font, label, wrapWidth, ChatFormatting.GRAY));
		for (String line : wrapToWidth(this.font, fingerprint, wrapWidth)) lines.add(VersionedText.literal(line).withStyle(ChatFormatting.GRAY));
		return lines;
	}

	private void copyDetails() {
		Minecraft.getInstance().keyboardHandler.setClipboard("Origin: " + origin + "\nExpected fingerprint: " + expectedFingerprint + "\nPresented fingerprint: " + presentedFingerprint);
		copied = true;
		rebuild();
	}

	private void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.pinMismatch.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.LIGHT_RED);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(origin).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), this.width / 2, 28, TextColors.WHITE);
		if (copied) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.error.copied").withStyle(ChatFormatting.GREEN), this.width / 2, 40, TextColors.WHITE);
		int y = 42 + (copied ? LINE : 0);
		for (MutableComponent line : bodyLines) {
			drawCenteredTextWithShadow(matrices, this.font, line, this.width / 2, y, TextColors.WHITE);
			y += LINE;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
