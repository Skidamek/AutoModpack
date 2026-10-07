package pl.skidam.automodpack.client.ui.widget;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScissor;

/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/**
 * The shared foundation of every list in the mod: {@link ListChrome} draws one list look identically on every
 * version, while the window, row geometry and scrollbar are pinned - vanilla's own chrome is stripped so no
 * version can render a list differently. The scrollbar is anchored right of the rows in absolute screen
 * coordinates - the vanilla relative position made every click inside a menu panel land on a phantom scrollbar.
 * Contract: the rows occupy exactly the [top, bottom] window the constructor is given, so a list whose content
 * fits never scrolls and never shows a scrollbar, whatever vanilla's internal first-row inset and content
 * padding are. Lists extending this only describe their rows. Rows are content-sized here (a 9px row is exactly
 * one font line), so a row draws from its raw row rectangle on every version - vanilla's content accessors carry
 * a 2px padding that pushes an 8px glyph past the row bottom, where the list scissor amputates the last row's
 * descenders. The one per-version entry fork lives in {@link Row}; list entries extend it and never fork
 * rendering again. The window geometry and the per-version list plumbing both read their numbers from
 * {@link ListChrome}, so {@link GroupSelectionList} - which cannot extend this because it needs
 * {@code ContainerObjectSelectionList} - pins the same rows from the same numbers.
 */
public abstract class UniformList<T extends ObjectSelectionList.Entry<T>> extends ObjectSelectionList<T> {
	private final int contentWidth;
	private final int rowHeight;

	protected UniformList(Minecraft client, int width, int screenHeight, int left, int top, int bottom, int contentWidth, int rowHeight) {
		/*? if <1.20.3 {*/
		/*super(client, width, screenHeight, ListChrome.windowTop(top), bottom, rowHeight);
		this.setLeftPos(left);
		*//*?} else {*/
		super(client, width, ListChrome.listHeight(rowHeight, top, bottom), ListChrome.windowTop(top), rowHeight);
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
		return ListChrome.scrollRange(this.contentHeight(), this.height);
	}

	/*?}*/

	/** Binds every row to this list, so the shared entry chrome can find the selection. */
	@Override
	protected int addEntry(T entry) {
		int index = super.addEntry(entry);
		if (entry instanceof Row<?> row) row.owner = this;
		return index;
	}

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
		return ListChrome.scrollbarPosition(this.getRowLeft(), this.getRowWidth());
	}
	*//*?}*/

	/*? if <1.19.4 {*/
	/*@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		VersionedMatrices chrome = new VersionedMatrices();
		ListChrome.drawBackdrop(chrome, x0, y0, x1, y1);
		// Separators precede super.render so the scrollbar paints over them, like vanilla's bands before its scrollbar.
		ListChrome.drawSeparators(chrome, x0, y0, x1, y1);
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(matrices, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?} elif <1.20.3 {*/
	/*@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		VersionedMatrices chrome = new VersionedMatrices(graphics);
		ListChrome.drawBackdrop(chrome, x0, y0, x1, y1);
		// Separators precede super.render so the scrollbar paints over them, like vanilla's bands before its scrollbar.
		ListChrome.drawSeparators(chrome, x0, y0, x1, y1);
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(graphics, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?}*/

	/*? if >=26.1 {*/
	@Override
	protected void extractListBackground(GuiGraphicsExtractor guiGraphics) {
		ListChrome.drawBackdrop(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor guiGraphics) {
		ListChrome.drawSeparators(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}
	/*?} elif >=1.21.10 {*/
	/*@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {
		ListChrome.drawBackdrop(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {
		ListChrome.drawSeparators(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}
	*//*?} elif >=1.21.1 {*/
	/*@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {
		ListChrome.drawBackdrop(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {
		ListChrome.drawSeparators(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), this.getRight(), this.getBottom());
	}

	@Override
	protected void renderSelection(GuiGraphics guiGraphics, int y, int entryWidth, int entryHeight, int outlineColor, int innerColor) {}
	*//*?} elif >=1.20.4 {*/
	/*@Override
	protected void renderSelection(GuiGraphics guiGraphics, int y, int entryWidth, int entryHeight, int outlineColor, int innerColor) {}
	*//*?}*/

	/**
	 * One row of any UniformList, and the only version fork a list entry ever needs: vanilla's per-version
	 * entry callbacks - the extract pipeline on 26.x, {@code renderContent} since 1.21.9, plain {@code render}
	 * before - all funnel into {@link #versionedRender} with the row's raw rectangle, after the shared entry
	 * chrome. Nothing here may route through vanilla's content accessors: their 2px padding assumes rows taller
	 * than their content, while these rows are exactly their content, so the padding would draw every row 2px low
	 * and the list scissor would cut the last row's descenders off.
	 */
	public abstract static class Row<E extends Row<E>> extends ObjectSelectionList.Entry<E> {
		/** The list this row belongs to; bound by UniformList's addEntry so the shared entry chrome can find the selection. A row is an inner class in every language but this one, because UniformList's element type is bounded by the row type and javac cannot resolve that cycle through an enclosing instance. */
		UniformList<?> owner;
		/*? if >=26.1 {*/
		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			VersionedMatrices matrices = new VersionedMatrices(guiGraphics);
			this.drawEntryChrome(matrices, this.getX(), this.getY(), this.getWidth(), hovered);
			this.versionedRender(matrices, this.getX(), this.getY(), this.getWidth(), mouseX, mouseY, hovered, tickDelta);
		}
		/*?} elif >= 1.21.9 {*/
		/*@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			VersionedMatrices matrices = new VersionedMatrices(guiGraphics);
			this.drawEntryChrome(matrices, this.getX(), this.getY(), this.getWidth(), hovered);
			this.versionedRender(matrices, this.getX(), this.getY(), this.getWidth(), mouseX, mouseY, hovered, tickDelta);
		}
		*//*?} else {*/
		/*@Override
		/^? if <1.20 {^/
		/^public void render(PoseStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			VersionedMatrices versionedMatrices = new VersionedMatrices();
		^//^?} else {^/
		public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			VersionedMatrices versionedMatrices = new VersionedMatrices(guiGraphics);
		/^?}^/
			this.drawEntryChrome(versionedMatrices, x, y, entryWidth, hovered);
			this.versionedRender(versionedMatrices, x, y, entryWidth, mouseX, mouseY, hovered, tickDelta);
		}
		*//*?}*/

		/** Whether the list holds this row as its selection; the one spelling of the predicate the chrome and the rows share. */
		public final boolean isSelected() {
			return owner.getSelected() == this;
		}

		/** Draws the shared row chrome under the row's own content: selection first, then the hover wash an unselected row wears while the pointer is on it. */
		private void drawEntryChrome(VersionedMatrices matrices, int x, int y, int width, boolean hovered) {
			if (isSelected()) ListChrome.drawSelection(matrices, x, y, width, owner.rowHeight(), owner.isFocused());
			else if (hovered) ListChrome.drawHover(matrices, x, y, width, owner.rowHeight());
		}

		/** Draws the row's content inside its raw rectangle [x, y, x + width, y + rowHeight]; absolute gui pixels. */
		protected abstract void versionedRender(VersionedMatrices matrices, int x, int y, int width, int mouseX, int mouseY, boolean hovered, float tickDelta);
	}
}
