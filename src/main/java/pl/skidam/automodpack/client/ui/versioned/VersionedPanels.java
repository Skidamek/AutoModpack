package pl.skidam.automodpack.client.ui.versioned;

/*? if <1.20 {*/
/*import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
*//*?} elif >=1.21.6 {*/
import net.minecraft.client.renderer.RenderPipelines;
/*?} else {*/
/*import net.minecraft.client.renderer.RenderType;
*//*?}*/

import net.minecraft.resources.Identifier;

import pl.skidam.automodpack.client.ClientTextures;
import pl.skidam.automodpack.init.Common;

/** Draws the tooltip panel - the dark sprite with the purple frame - nine-sliced over any rectangle, and owns the
 * overlay frame recipe (flush, one z layer up on the versions with a gui depth, draw, flush) that makes panels paint
 * over screen text on the versions whose gui batching would otherwise order them differently. The panel sprites are
 * our bundled copies of the vanilla panels, drawn through the TextureManager on every version, where no pushed
 * resource pack can stand in for them.
 */
public final class VersionedPanels {
	private VersionedPanels() {}

	/**
	 * Begins a top-most overlay layer (dropdown menus, tooltips): everything until {@link #endOverlay} paints above
	 * all screen content. This is the vanilla drawManaged recipe for tooltips - flush what is pending, step up one
	 * z layer, draw, flush - because a plain later draw loses the race against the gui batch ordering on some
	 * versions. 26.x gets a fresh stratum instead, which is its own version of the same guarantee.
	 */
	public static void beginOverlay(VersionedMatrices matrices) {
		/*? if <1.20 {*/
		/*Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
		matrices.translate(0, 0, 400);
		*//*?} elif <1.21.6 {*/
		/*matrices.getContext().flush();
		matrices.getContext().pose().translate(0.0F, 0.0F, 400.0F);
		*//*?} elif >=26.1 {*/
		matrices.getContext().nextStratum();
		/*?}*/
	}

	public static void endOverlay(VersionedMatrices matrices) {
		/*? if <1.20 {*/
		/*matrices.translate(0, 0, -400);
		Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
		*//*?} elif <1.21.6 {*/
		/*matrices.getContext().pose().translate(0.0F, 0.0F, -400.0F);
		matrices.getContext().flush();
		*//*?}*/
	}

	public static void drawTooltipPanel(VersionedMatrices matrices, int x, int y, int width, int height) {
		ClientTextures.ensureRegistered();
		drawNineSlice(panelSprite(true), matrices, x, y, width, height, 10);
		drawNineSlice(panelSprite(false), matrices, x, y, width, height, 9);
	}

	private static Identifier panelSprite(boolean frame) {
		return Common.id("textures/gui/sprites/tooltip/" + (frame ? "frame" : "background") + ".png");
	}

	/** Draws the panel sprite nine-sliced over the rectangle: four crisp corners, stretched edges and center, from the bundled 100x100 texture. */
	private static void drawNineSlice(Identifier texture, VersionedMatrices matrices, int x, int y, int width, int height, int border) {
		int b = Math.min(border, Math.min(width, height) / 2);
		int right = x + width;
		int bottom = y + height;
		int innerW = 100 - 2 * b;
		int innerH = 100 - 2 * b;
		blitPatch(texture, matrices, x, y, b, b, 0, 0, b, b, 100, 100);
		blitPatch(texture, matrices, right - b, y, b, b, 100 - b, 0, b, b, 100, 100);
		blitPatch(texture, matrices, x, bottom - b, b, b, 0, 100 - b, b, b, 100, 100);
		blitPatch(texture, matrices, right - b, bottom - b, b, b, 100 - b, 100 - b, b, b, 100, 100);
		blitPatch(texture, matrices, x + b, y, width - 2 * b, b, b, 0, innerW, b, 100, 100);
		blitPatch(texture, matrices, x + b, bottom - b, width - 2 * b, b, b, 100 - b, innerW, b, 100, 100);
		blitPatch(texture, matrices, x, y + b, b, height - 2 * b, 0, b, b, innerH, 100, 100);
		blitPatch(texture, matrices, right - b, y + b, b, height - 2 * b, 100 - b, b, b, innerH, 100, 100);
		blitPatch(texture, matrices, x + b, y + b, width - 2 * b, height - 2 * b, b, b, innerW, innerH, 100, 100);
	}

	/** One nine-slice patch: texture region (u, v, srcWidth, srcHeight) stretched into (x, y, destWidth, destHeight). */
	/*? if <1.20 {*/
	/*private static void blitPatch(Identifier texture, VersionedMatrices matrices, int x, int y, int destWidth, int destHeight, int u, int v, int srcWidth, int srcHeight, int textureWidth, int textureHeight) {
		RenderSystem.setShaderTexture(0, texture);
		GuiComponent.blit(matrices.getContext(), x, y, destWidth, destHeight, (float) u, (float) v, srcWidth, srcHeight, textureWidth, textureHeight);
	}
	*//*?} elif <1.21.2 {*/
	/*private static void blitPatch(Identifier texture, VersionedMatrices matrices, int x, int y, int destWidth, int destHeight, int u, int v, int srcWidth, int srcHeight, int textureWidth, int textureHeight) {
		matrices.getContext().blit(texture, x, y, destWidth, destHeight, (float) u, (float) v, srcWidth, srcHeight, textureWidth, textureHeight);
	}
	*//*?} elif <1.21.6 {*/
	/*private static void blitPatch(Identifier texture, VersionedMatrices matrices, int x, int y, int destWidth, int destHeight, int u, int v, int srcWidth, int srcHeight, int textureWidth, int textureHeight) {
		matrices.getContext().blit(RenderType::guiTextured, texture, x, y, (float) u, (float) v, destWidth, destHeight, srcWidth, srcHeight, textureWidth, textureHeight);
	}
	*//*?} else {*/
	private static void blitPatch(Identifier texture, VersionedMatrices matrices, int x, int y, int destWidth, int destHeight, int u, int v, int srcWidth, int srcHeight, int textureWidth, int textureHeight) {
		matrices.getContext().blit(RenderPipelines.GUI_TEXTURED, texture, x, y, (float) u, (float) v, destWidth, destHeight, srcWidth, srcHeight, textureWidth, textureHeight);
	}
	/*?}*/
}
