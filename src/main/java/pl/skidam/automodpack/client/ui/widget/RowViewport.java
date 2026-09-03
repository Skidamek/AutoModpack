package pl.skidam.automodpack.client.ui.widget;

/** Brings a selection list's row into view and reports what tooling can see in it, so scrolled-out rows stay readable and clickable. */
public interface RowViewport {
	/** Scrolls the row into view and syncs its hit-test rectangles, so a click computed from the row geometry lands even before the next render. */
	void revealRow(int index);

	/** What tooling sees in one row: its text, whether it accepts clicks and, for checkbox rows, its checked state. */
	RowView rowView(int index);

	int rowCount();

	int rowLeft();

	int rowTop(int index);

	int rowWidth();

	int rowHeight();

	record RowView(String text, boolean enabled, Boolean checked) {}
}
