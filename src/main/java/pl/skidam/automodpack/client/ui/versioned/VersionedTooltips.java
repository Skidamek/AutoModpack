package pl.skidam.automodpack.client.ui.versioned;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.TextColors;

/**
 * The one tooltip implementation for every Minecraft version: wrapped lines, anchored below the pointer, clamped
 * into the window so it never flips off-screen, painted from plain fills and text. Vanilla anchors and paints its
 * tooltips differently on every version (legacy flips to the left of the pointer, 26.x defers to the next frame and
 * sprites the panel), so none of it can be shared - the panel look here is the classic vanilla tooltip colors that
 * every version still ships.
 */
public final class VersionedTooltips {
	private VersionedTooltips() {}

	private static final int BACKGROUND = 0xF0100010;
	private static final int BORDER = 0x505000FF;
	private static final int BORDER_BOTTOM = 0x5028FFFF;
	/** The pointer-to-box gap, vanilla's own mouse offset. */
	private static final int MOUSE_OFFSET = 12;
	private static final int SCREEN_MARGIN = 4;
	private static final int TEXT_PAD_X = 5;
	private static final int TEXT_PAD_Y = 4;
	/** Vanilla advances tooltip lines by ten pixels: one font line plus one of leading. */
	private static final int LINE_STEP = VersionedScreen.LINE_HEIGHT + 1;
	/** The hard ceiling comes from the widest content we ship (a sha1 line is ~240px); narrow windows clamp further. */
	private static final int MAX_WRAP_WIDTH = 300;

	public static void draw(Font font, VersionedMatrices matrices, Component tooltip, int anchorX, int anchorY, int screenWidth, int screenHeight) {
		int windowWidth = Math.max(1, screenWidth - 2 * MOUSE_OFFSET - 2 * SCREEN_MARGIN);
		List<MutableComponent> lines = VersionedScreen.wrapParagraph(font, tooltip.getString(), Math.max(1, Math.min(MAX_WRAP_WIDTH, windowWidth)));
		int textWidth = 0;
		for (MutableComponent line : lines) textWidth = Math.max(textWidth, font.width(line));
		int textHeight = (lines.size() - 1) * LINE_STEP + font.lineHeight;
		int boxWidth = textWidth + TEXT_PAD_X * 2;
		int boxHeight = textHeight + TEXT_PAD_Y * 2;
		int x = clamp(anchorX + MOUSE_OFFSET, boxWidth, screenWidth);
		int y = clamp(anchorY + MOUSE_OFFSET, boxHeight, screenHeight);
		matrices.fill(x, y, x + boxWidth, y + 1, BORDER);
		matrices.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, BORDER_BOTTOM);
		matrices.fill(x, y + 1, x + 1, y + boxHeight - 1, BORDER);
		matrices.fill(x + boxWidth - 1, y + 1, x + boxWidth, y + boxHeight - 1, BORDER);
		matrices.fill(x + 1, y + 1, x + boxWidth - 1, y + boxHeight - 1, BACKGROUND);
		int textY = y + TEXT_PAD_Y;
		for (MutableComponent line : lines) {
			VersionedScreen.drawTextWithShadow(matrices, font, line, x + TEXT_PAD_X, textY, TextColors.WHITE);
			textY += LINE_STEP;
		}
	}

	/** Keeps the box inside the window: past the right or bottom edge it slides back, never past the opposite margin. */
	private static int clamp(int anchor, int boxSize, int windowSize) {
		return Math.max(SCREEN_MARGIN, Math.min(anchor, windowSize - SCREEN_MARGIN - boxSize));
	}
}
