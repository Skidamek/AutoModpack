package pl.skidam.automodpack.client.ui.versioned;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

/** GL scissor over a gui-coordinate rectangle; the counterpart vanilla applies to its own lists from 1.20.3 on. */
public final class VersionedScissor {
	private VersionedScissor() {}

	/**
	 * Clips all following rendering to the rectangle; pair with {@link #disable()} right after. Pending text is flushed
	 * first so anything drawn outside this scope rasterizes before the scissor turns on, never inside it.
	 */
	public static void enable(Minecraft client, int x0, int y0, int x1, int y1) {
		Window window = client.getWindow();
		double scale = window.getGuiScale();
		/*? if >=1.21.8 {*/
		RenderSystem.enableScissorForRenderTypeDraws((int) (x0 * scale), (int) (window.getHeight() - y1 * scale), (int) ((x1 - x0) * scale), (int) ((y1 - y0) * scale));
		/*?} else {*/
		/*Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
		RenderSystem.enableScissor((int) (x0 * scale), (int) (window.getHeight() - y1 * scale), (int) ((x1 - x0) * scale), (int) ((y1 - y0) * scale));
		*//*?}*/
	}

	/**
	 * Ends the scope. The text batch is flushed first because deferred glyphs rasterize at flush time, not draw time:
	 * left pending, they would rasterize inside whatever other widget's scope happens to flush next and get clipped to
	 * that box. From 1.21.8 on vanilla's own scissor handles the flush contract for us.
	 */
	public static void disable() {
		/*? if >=1.21.8 {*/
		RenderSystem.disableScissorForRenderTypeDraws();
		/*?} else {*/
		/*Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
		RenderSystem.disableScissor();
		*//*?}*/
	}
}
