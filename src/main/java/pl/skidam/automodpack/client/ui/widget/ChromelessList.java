package pl.skidam.automodpack.client.ui.widget;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;

import pl.skidam.automodpack.client.ui.versioned.VersionedScissor;

/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/**
 * The shared foundation of every list in the mod: vanilla chrome (dirt bands, panel background, separators,
 * selection outline) is stripped identically on all versions, rows are clipped to the list on the versions that
 * do not clip themselves, and the scrollbar is anchored right of the rows in absolute screen coordinates - the
 * vanilla relative position made every click inside a menu panel land on a phantom scrollbar. Contract: the rows
 * occupy exactly the [top, bottom] window the constructor is given, so a list whose content fits never scrolls
 * and never shows a scrollbar, whatever vanilla's internal first-row inset and content padding are. Lists
 * extending this only describe their rows.
 */
public abstract class ChromelessList<T extends ObjectSelectionList.Entry<T>> extends ObjectSelectionList<T> {
	/** Vanilla renders the first row this far below the list's top edge; the window is shifted up so rows land where the caller asked. */
	/*? if >=1.21.10 {*/
	private static final int VANILLA_ROW_INSET = 2;
	/*?} else {*/
	/*private static final int VANILLA_ROW_INSET = 4;
	*//*?}*/
	private final int contentWidth;
	private final int rowHeight;

	protected ChromelessList(Minecraft client, int width, int screenHeight, int left, int top, int bottom, int contentWidth, int rowHeight) {
		/*? if <1.20.3 {*/
		/*super(client, width, screenHeight, top - VANILLA_ROW_INSET, bottom, rowHeight);
		this.setLeftPos(left);
		*//*?} else {*/
		super(client, width, Math.max(rowHeight, bottom - top) + VANILLA_ROW_INSET, top - VANILLA_ROW_INSET, rowHeight);
		this.setX(left);
		/*?}*/
		this.contentWidth = Math.max(1, contentWidth);
		this.rowHeight = rowHeight;
		this.centerListVertically = false;
		/*? if <1.21.1 {*/
		/*this.setRenderBackground(false);
		this.setRenderTopAndBottom(false);
		*//*?}*/
		/*? if <1.20.4 {*/
		/*this.setRenderSelection(false);
		*//*?}*/
	}

	/*? if >=1.21.10 {*/
	@Override
	public int maxScrollAmount() {
		// Vanilla pads contentHeight by 4 while its rows only start 2px down; with the window shifted onto the rows, the scroll range is exactly the overflow.
		return Math.max(0, this.contentHeight() - this.height - (4 - VANILLA_ROW_INSET));
	}

	/*?}*/
	/** Scrolls the row into view; lists with hit-test pinned entries sync their rectangles through {@link #pinEntry}. */
	public final void revealRow(int index) {
		T entry = this.children().get(index);
		/*? if >=1.21.9 {*/
		this.scrollToEntry(entry);
		/*?} else {*/
		/*this.ensureVisible(entry);
		*//*?}*/
		pinEntry(entry, index);
	}

	/** Pins a row's hit-test rectangle to its live position; only lists the tooling clicks into need this. */
	protected void pinEntry(T entry, int index) {}

	public final int rowCount() {
		return this.children().size();
	}

	public final int rowLeft() {
		return this.getRowLeft();
	}

	public final int rowTop(int index) {
		return this.getRowTop(index);
	}

	public final int rowWidth() {
		return this.contentWidth;
	}

	public final int rowHeight() {
		return this.rowHeight;
	}

	public int getRowWidth() {
		return this.contentWidth;
	}

	/*? if <1.21.4 {*/
	/*@Override
	protected int getScrollbarPosition() {
		// The hit test compares absolute screen coordinates, so the anchor has to be absolute too.
		return this.getRowLeft() + this.getRowWidth() + 4;
	}
	*//*?}*/

	/*? if <1.19.4 {*/
	/*@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(matrices, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?} elif <1.20.3 {*/
	/*@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(graphics, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?}*/

	/*? if >=26.1 {*/
	@Override
	protected void extractListBackground(GuiGraphicsExtractor guiGraphics) {}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor guiGraphics) {}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}
	/*?} elif >=1.21.10 {*/
	/*@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}
	*//*?} elif >=1.21.1 {*/
	/*@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {}

	@Override
	protected void renderSelection(GuiGraphics guiGraphics, int y, int entryWidth, int entryHeight, int outlineColor, int innerColor) {}
	*//*?} elif >=1.20.4 {*/
	/*@Override
	protected void renderSelection(GuiGraphics guiGraphics, int y, int entryWidth, int entryHeight, int outlineColor, int innerColor) {}
	*//*?}*/
}
