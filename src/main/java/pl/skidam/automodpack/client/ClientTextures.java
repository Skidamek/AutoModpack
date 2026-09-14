package pl.skidam.automodpack.client;

import java.io.InputStream;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import pl.skidam.automodpack.init.Common;

import static pl.skidam.automodpack_core.Constants.LOGGER;

/** Uploads our bundled gui sprites as in-memory dynamic textures under the exact ids the draw sites blit. The vanilla
 * TextureManager then answers every lookup from memory and never consults a resource pack, so a server-pushed pack
 * cannot stand in for our icons, progress bars or tooltip panels (pre-atlas versions lazily created a pack-backed
 * SimpleTexture for unknown ids instead - that is the hole this closes). Registration is lazy: the draw funnel calls
 * in on the render thread, where the texture manager exists on every loader.
 */
public final class ClientTextures {
	/** Every bundled sprite that has a draw site; ids are file ids under textures/gui/sprites. */
	private static final List<String> SPRITES = List.of("green_background", "green_progress", "checkbox", "folder", "music-note", "mute-music-note", "tooltip/background", "tooltip/frame");

	private static volatile boolean registered = false;

	private ClientTextures() {}

	/** Idempotent, render-thread only: every draw site calls this right before blitting one of our ids. */
	public static void ensureRegistered() {
		if (registered) return;
		synchronized (ClientTextures.class) {
			if (registered) return;
			TextureManager textureManager = Minecraft.getInstance().getTextureManager();
			for (String sprite : SPRITES) {
				register(textureManager, sprite);
			}
			registered = true;
		}
	}

	private static void register(TextureManager textureManager, String sprite) {
		Identifier id = Common.id("textures/gui/sprites/" + sprite + ".png");
		try (InputStream stream = ClientTextures.class.getResourceAsStream("/assets/automodpack/textures/gui/sprites/" + sprite + ".png")) {
			if (stream == null) {
				LOGGER.error("Bundled sprite {} is missing from the jar", id);
				return;
			}
			NativeImage image = NativeImage.read(stream);
			textureManager.register(id, dynamicTexture(sprite, image));
		} catch (Exception e) {
			LOGGER.error("Failed to load bundled sprite {}", id, e);
		}
	}

	private static DynamicTexture dynamicTexture(String sprite, NativeImage image) {
		/*? if <1.21.5 {*/
		/*return new DynamicTexture(image);
		*//*?} else {*/
		return new DynamicTexture(() -> "AutoModpack " + sprite, image);
		/*?}*/
	}
}
