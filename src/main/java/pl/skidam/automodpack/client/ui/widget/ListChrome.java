package pl.skidam.automodpack.client.ui.widget;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;

/**
 * The one list look, drawn identically on every version; every color is vanilla's own, measured from its sprites. The
 * window geometry the shared lists pin also lives here, so the numbers that make a list's rows land where the caller
 * asked have one home: {@link #listHeight} and {@link #windowTop} shift the vanilla window onto the rows,
 * {@link #scrollbarPosition} anchors the scrollbar in absolute screen coordinates, and {@link #scrollRange} is the exact
 * overflow once that shift is applied. The lists differ only in which vanilla base class they extend.
 */
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

	/** Vanilla renders the first row this far below the list's top edge; the window is shifted up so rows land where the caller asked. */
	/*? if >=1.21.10 {*/
	public static final int ROW_INSET = 2;
	/*?} else {*/
	/*public static final int ROW_INSET = 4;
	*//*?}*/

	private ListChrome() {}

	/** The window height that puts rows in [top, bottom]: the caller's span plus the inset vanilla reserves above the first row. */
	public static int listHeight(int rowHeight, int top, int bottom) {
		return Math.max(rowHeight, bottom - top) + ROW_INSET;
	}

	/** The window origin that cancels vanilla's inset, so the first row starts exactly at the caller's top. */
	public static int windowTop(int top) {
		return top - ROW_INSET;
	}

	/** The scroll range once the window sits on the rows: vanilla pads content height by 4 while its rows start 2px down. */
	public static int scrollRange(int contentHeight, int height) {
		return Math.max(0, contentHeight - height - (4 - ROW_INSET));
	}

	/** The hit test compares absolute screen coordinates, so the scrollbar anchor has to be absolute too. */
	public static int scrollbarPosition(int rowLeft, int rowWidth) {
		return rowLeft + rowWidth + 4;
	}

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
