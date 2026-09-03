package pl.skidam.automodpack.client.ui.widget;

import java.util.List;

import net.minecraft.client.gui.components.ObjectSelectionList;

/** Brings a selection list's row into view and reports where it currently sits, so tools can read and click scrolled-out rows. */
public interface RowViewport {
	void revealRow(int index);

	List<? extends ObjectSelectionList.Entry<?>> entries();

	int rowLeft();

	int rowTop(int index);

	int rowWidth();

	int rowHeight();
}
