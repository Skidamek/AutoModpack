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
import pl.skidam.automodpack_core.utils.UriOpener;

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
	private int titleTop;

	public PinMismatchScreen(Screen parent, String origin, String expectedFingerprint, String presentedFingerprint) {
		super(VersionedText.text("automodpack.pinMismatch.title"));
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
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.pinMismatch.happened", origin), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrappedFingerprint(VersionedText.str("automodpack.pinMismatch.pinned"), expectedFingerprint, wrapWidth));
		lines.addAll(wrappedFingerprint(VersionedText.str("automodpack.pinMismatch.presented"), presentedFingerprint, wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.pinMismatch.meaning"), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.pinMismatch.do"), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.pinMismatch.dont"), wrapWidth, ChatFormatting.RED));
		ActionRow copyRow = actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.text("automodpack.error.copyDetails"), button -> copyDetails()),
				optionalAction(VersionedText.text("automodpack.learnmore"), button -> UriOpener.openUri(SECURITY_DOCS_URL + "#certificate-mismatch")));
		ActionRow footerRow = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), button -> ScreenImpl.setScreen(parent)));
		// The header is part of the centered block: title and origin, plus the "copied" confirmation line while it shows.
		int headerLines = 2 + (copied ? 1 : 0);
		DialogLayout layout = layoutDialogWithActions(28, headerLines * LINE_HEIGHT, lines.size() * LINE_HEIGHT, 0, copyRow, footerRow);
		this.titleTop = layout.titleTop();
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, copyRow, footerRow);
		addCenteredScrollBody(BODY, layout.column().bodyTop(), layout.column().bodyBottom(), lines);
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.pinMismatch.title").withStyle(ChatFormatting.BOLD), this.width / 2, titleTop, TextColors.LIGHT_RED);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(origin).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), this.width / 2, titleTop + LINE_HEIGHT, TextColors.WHITE);
		if (copied) drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.error.copied").withStyle(ChatFormatting.GREEN), this.width / 2, titleTop + 2 * LINE_HEIGHT, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
