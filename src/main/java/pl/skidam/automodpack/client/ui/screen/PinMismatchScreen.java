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
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/**
 * Security rejection for a pinned certificate that no longer matches. States what happened, what it can mean,
 * and what to do; it deliberately offers no way past the pin.
 */
public final class PinMismatchScreen extends VersionedScreen {
	private static final int BODY = 420;
	private final Screen parent;
	private final String origin;
	private final String expectedFingerprint;
	private final String presentedFingerprint;
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
		ActionRow copyRow = actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.error.copyDetails"), button -> copyDetails()));
		ActionRow footerRow = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), button -> ScreenImpl.setScreen(parent)));
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, copyRow, footerRow);
		// The body starts below the pinned header; the "copied" confirmation shifts it down one line while it shows.
		DialogColumn column = layoutDialogColumn(42 + (copied ? LINE_HEIGHT : 0), actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, copyRow, footerRow), lines.size() * LINE_HEIGHT, 0);
		addCenteredScrollBody(BODY, column.bodyTop(), column.bodyBottom(), lines);
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

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.pinMismatch.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.LIGHT_RED);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(origin).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), this.width / 2, 28, TextColors.WHITE);
		if (copied) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.error.copied").withStyle(ChatFormatting.GREEN), this.width / 2, 40, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
