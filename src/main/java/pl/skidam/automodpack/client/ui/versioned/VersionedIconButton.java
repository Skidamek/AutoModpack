package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/** The vanilla icon-only button look with the icon drawn from our own texture through the TextureManager instead of
 * the gui atlas: vanilla's SpriteIconButton and ImageButton read their pixels through resource packs on the versions
 * we support, and no server-pushed pack may stand in for our icons.
 */
public final class VersionedIconButton extends Button {
	private final Identifier texture;
	private final int spriteWidth;

	public VersionedIconButton(int x, int y, int size, int spriteWidth, OnPress onPress, Identifier texture, Component message) {
		/*? if <1.19.3 {*/
		/*super(x, y, size, size, message, onPress);
		*//*?} else {*/
		super(x, y, size, size, message, onPress, DEFAULT_NARRATION);
		/*?}*/
		this.texture = texture;
		this.spriteWidth = spriteWidth;
	}

	/*? if >=26.1 {*/
	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		this.extractDefaultSprite(graphics);
		VersionedScreen.drawTexture(texture, new VersionedMatrices(graphics), iconLeft(), iconTop(), 0, 0, spriteWidth, spriteWidth, spriteWidth, spriteWidth);
	}
	/*?} elif >=1.21.11 {*/
	/*@Override
	public void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		this.renderDefaultSprite(graphics);
		VersionedScreen.drawTexture(texture, new VersionedMatrices(graphics), iconLeft(), iconTop(), 0, 0, spriteWidth, spriteWidth, spriteWidth, spriteWidth);
	}
	*//*?} elif >=1.20 {*/
	/*@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.renderWidget(graphics, mouseX, mouseY, delta);
		VersionedScreen.drawTexture(texture, new VersionedMatrices(graphics), iconLeft(), iconTop(), 0, 0, spriteWidth, spriteWidth, spriteWidth, spriteWidth);
	}
	*//*?} else {*/
	/*@Override
	public void renderButton(PoseStack matrices, int mouseX, int mouseY, float delta) {
		super.renderButton(matrices, mouseX, mouseY, delta);
		VersionedScreen.drawTexture(texture, new VersionedMatrices(), iconLeft(), iconTop(), 0, 0, spriteWidth, spriteWidth, spriteWidth, spriteWidth);
	}
	*//*?}*/

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
