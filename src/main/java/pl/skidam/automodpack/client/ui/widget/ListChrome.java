package pl.skidam.automodpack.client.ui.widget;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;

/** The one list look, drawn identically on every version; every color is vanilla's own, measured from its sprites. */
public final class ListChrome {
	/** vanilla menu_list_background.png: flat black at 112/255 alpha. */
	public static final int BACKDROP = 0x70000000;
	/** vanilla header/footer_separator.png: a two-tone bevel - white at 51/255 on the outer row, black at 191/255 against the list. */
	public static final int SEPARATOR_EDGE = 0x33FFFFFF;
	public static final int SEPARATOR_INNER = 0xBF000000;
	/** vanilla selection: gray outline, white when the list holds keyboard focus, solid black inner. */
	public static final int SELECTION_OUTLINE = 0xFF808080;
	public static final int SELECTION_OUTLINE_FOCUSED = 0xFFFFFFFF;
	public static final int SELECTION_INNER = 0xFF000000;
	/** The hovered-row wash reads as "you can click this" without ever lightening the backdrop. */
	public static final int HOVER = 0x33000000;

	private ListChrome() {}

	public static void drawBackdrop(VersionedMatrices matrices, int x0, int y0, int x1, int y1) {
		matrices.fill(x0, y0, x1, y1, BACKDROP);
	}

	public static void drawSeparators(VersionedMatrices matrices, int x0, int y0, int x1, int y1) {
		matrices.fill(x0, y0 - 2, x1, y0 - 1, SEPARATOR_EDGE);
		matrices.fill(x0, y0 - 1, x1, y0, SEPARATOR_INNER);
		matrices.fill(x0, y1, x1, y1 + 1, SEPARATOR_INNER);
		matrices.fill(x0, y1 + 1, x1, y1 + 2, SEPARATOR_EDGE);
	}

	public static void drawSelection(VersionedMatrices matrices, int x, int y, int width, int height, boolean focused) {
		matrices.fill(x, y, x + width, y + height, focused ? SELECTION_OUTLINE_FOCUSED : SELECTION_OUTLINE);
		matrices.fill(x + 1, y + 1, x + width - 1, y + height - 1, SELECTION_INNER);
	}

	public static void drawHover(VersionedMatrices matrices, int x, int y, int width, int height) {
		matrices.fill(x, y, x + width, y + height, HOVER);
	}
}
