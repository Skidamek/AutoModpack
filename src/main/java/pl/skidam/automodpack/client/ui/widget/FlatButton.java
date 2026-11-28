package pl.skidam.automodpack.client.ui.widget;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;

/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/** A list control drawn as a flat card: the same fill and outline frame as the rows it sits beside, on every version. */
abstract class FlatButton extends Button {
	protected FlatButton(int x, int y, int width, int height, Component message, OnPress onPress) {
		/*? if <1.19.3 {*/
		/*super(x, y, width, height, message, onPress);
		*//*?} else {*/
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
		/*?}*/
	}

	/*? if >=26.1 {*/
	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		renderFlat(new VersionedMatrices(graphics), mouseX, mouseY);
	}
	/*?} elif >=1.21.11 {*/
	/*@Override
	public void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		renderFlat(new VersionedMatrices(graphics), mouseX, mouseY);
	}
	*//*?} elif >=1.20 {*/
	/*@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		renderFlat(new VersionedMatrices(graphics), mouseX, mouseY);
	}
	*//*?} else {*/
	/*@Override
	public void renderButton(PoseStack matrices, int mouseX, int mouseY, float delta) {
		renderFlat(new VersionedMatrices(), mouseX, mouseY);
	}
	*//*?}*/

	/** Draws this control's own content inside the frame {@link #renderFlat} already painted. */
	protected abstract void drawContent(VersionedMatrices matrices, int left, int top, int width, int height, boolean highlighted);

	private void renderFlat(VersionedMatrices matrices, int mouseX, int mouseY) {
		int left = left();
		int top = top();
		int width = getWidth();
		int height = getHeight();
		boolean hovered = mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + height;
		boolean highlighted = this.active && (hovered || isFocused());
		ListChrome.drawCard(matrices, left, top, width, height, false, highlighted, this.active);
		drawContent(matrices, left, top, width, height, highlighted);
	}

	// Positions are read live: lists build rows at 0,0 and move the widgets during layout.
	protected int left() {
		/*? if >=1.19.4 {*/
		return getX();
		/*?} else {*/
		/*return this.x;
		*//*?}*/
	}

	protected int top() {
		/*? if >=1.19.4 {*/
		return getY();
		/*?} else {*/
		/*return this.y;
		*//*?}*/
	}
}
