package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import pl.skidam.automodpack.init.Common;

/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/** The vanilla icon-only button look with the icon drawn from our own texture through the TextureManager instead of
 * the gui atlas: vanilla's SpriteIconButton and ImageButton read their pixels through resource packs on the versions
 * we support, and no server-pushed pack may stand in for our icons. A toggle swaps its own sprite through
 * {@link #setSprite}, so one widget carries both of its states.
 */
public final class VersionedIconButton extends Button {
	private String sprite;
	private Component narration;
	private final int spriteWidth;

	public VersionedIconButton(int x, int y, int size, int spriteWidth, OnPress onPress, String sprite, Component message) {
		/*? if <1.19.3 {*/
		/*super(x, y, size, size, message, onPress);
		*//*?} else {*/
		super(x, y, size, size, message, onPress, DEFAULT_NARRATION);
		/*?}*/
		this.sprite = sprite;
		this.narration = message;
		this.spriteWidth = spriteWidth;
	}

	public void setSprite(String sprite) {
		this.sprite = sprite;
	}

	/** The drawn message stays empty - a label inside a 20px button clips mid-word - so the name a screen reader gets is carried here instead. */
	public void setNarration(Component narration) {
		this.narration = narration;
	}

	/*? if <1.19.4 {*/
/*@Override
	public void updateNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, narration);
	}
*//*?} else {*/
	@Override
	public void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, narration);
	}
/*?}*/

	/*? if >=26.1 {*/
	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		this.extractDefaultSprite(graphics);
		VersionedScreen.drawTexture(texture(), new VersionedMatrices(graphics), iconLeft(), iconTop(), 0, 0, spriteWidth, spriteWidth, spriteWidth, spriteWidth);
	}
	/*?} elif >=1.21.11 {*/
	/*@Override
	public void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		this.renderDefaultSprite(graphics);
		VersionedScreen.drawTexture(texture(), new VersionedMatrices(graphics), iconLeft(), iconTop(), 0, 0, spriteWidth, spriteWidth, spriteWidth, spriteWidth);
	}
	*//*?} elif >=1.20 {*/
	/*@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.renderWidget(graphics, mouseX, mouseY, delta);
		VersionedScreen.drawTexture(texture(), new VersionedMatrices(graphics), iconLeft(), iconTop(), 0, 0, spriteWidth, spriteWidth, spriteWidth, spriteWidth);
	}
	*//*?} else {*/
	/*@Override
	public void renderButton(PoseStack matrices, int mouseX, int mouseY, float delta) {
		super.renderButton(matrices, mouseX, mouseY, delta);
		VersionedScreen.drawTexture(texture(), new VersionedMatrices(), iconLeft(), iconTop(), 0, 0, spriteWidth, spriteWidth, spriteWidth, spriteWidth);
	}
	*//*?}*/

	private Identifier texture() {
		return Common.id("textures/gui/sprites/" + sprite + ".png");
	}

	// Positions are read live: lists build rows at 0,0 and move the widgets during layout.
	private int iconLeft() {
		/*? if >=1.19.4 {*/
		return getX() + (this.width - spriteWidth) / 2;
		/*?} else {*/
		/*return this.x + (this.width - spriteWidth) / 2;
		*//*?}*/
	}

	private int iconTop() {
		/*? if >=1.19.4 {*/
		return getY() + (this.height - spriteWidth) / 2;
		/*?} else {*/
		/*return this.y + (this.height - spriteWidth) / 2;
		*//*?}*/
	}
}
