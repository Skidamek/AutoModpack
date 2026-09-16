package pl.skidam.automodpack.client.ui.versioned;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.TextColors;

/**
 * The one tooltip implementation for every Minecraft version: the vanilla 26.1 tooltip - its exact panel sprites,
 * padding and line metrics - wrapped to the window and anchored below the pointer so it never flips off-screen.
 * Vanilla anchors and paints its tooltips differently on every version (legacy flips to the left of the pointer,
 * 1.20 batches the panel under already-drawn text, 26.1 defers to a sprite panel in its own stratum), so none of
 * it can be shared - the look here is the 26.1 look, and the draw runs inside {@link VersionedScreen#beginOverlay}
 * which is the vanilla drawManaged recipe: flush, z-layer, draw, flush.
 */
public final class VersionedTooltips {
	private VersionedTooltips() {}

	/** The sprite rect is the text rect plus this on every side: nine pixels of soft sprite edge plus three of padding. */
	private static final int SPRITE_MARGIN = 12;
	/** The pointer-to-box gap, vanilla's own mouse offset. */
	private static final int MOUSE_OFFSET = 12;
	private static final int SCREEN_MARGIN = 4;
	/** Vanilla 26.1 wraps hover text at half the gui width, never under 200. */
	private static final int MIN_WRAP_WIDTH = 200;

	public static void draw(Font font, VersionedMatrices matrices, Component tooltip, int anchorX, int anchorY, int screenWidth, int screenHeight) {
		List<MutableComponent> lines = VersionedScreen.wrapParagraph(font, tooltip.getString(), Math.max(MIN_WRAP_WIDTH, screenWidth / 2));
		int textWidth = 0;
		for (MutableComponent line : lines) textWidth = Math.max(textWidth, font.width(line));
		// Vanilla 26.1 metrics: single-line boxes lose two pixels, multi-line ones gain a two pixel gap after the first line.
		int textHeight = 9 * lines.size() + (lines.size() > 1 ? 2 : -2);
		int x = clamp(anchorX + MOUSE_OFFSET, textWidth, screenWidth);
		int y = clamp(anchorY + MOUSE_OFFSET, textHeight, screenHeight);
		VersionedPanels.beginOverlay(matrices);
		VersionedPanels.drawTooltipPanel(matrices, x - SPRITE_MARGIN, y - SPRITE_MARGIN, textWidth + 2 * SPRITE_MARGIN, textHeight + 2 * SPRITE_MARGIN);
		int textY = y;
		for (int index = 0; index < lines.size(); index++) {
			VersionedScreen.drawTextWithShadow(matrices, font, lines.get(index), x, textY, TextColors.WHITE);
			textY += index == 0 ? VersionedScreen.LINE_HEIGHT + 2 : VersionedScreen.LINE_HEIGHT;
		}
		VersionedPanels.endOverlay(matrices);
	}

	/** Keeps the box inside the window: past the right or bottom edge it slides back, never past the opposite margin. */
	private static int clamp(int anchor, int boxSize, int windowSize) {
		return Math.max(SCREEN_MARGIN, Math.min(anchor, windowSize - SCREEN_MARGIN - boxSize));
	}
}
