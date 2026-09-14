package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
/*? if <= 1.19.1 {*/
/*import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
*//*?}*/

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.text.L10n;

public class VersionedText {

	/*? if <=1.19.1 {*/
	/*public static MutableComponent translatable(String key, Object... args) {
		return new TranslatableComponent(key, args);
	}

	public static MutableComponent literal(String string) {
		return new TextComponent(string);
	}

	*//*?} else {*/
	public static MutableComponent translatable(String key, Object... args) {
		return Component.translatable(key, args);
	}

	public static MutableComponent literal(String string) {
		return Component.literal(string);
	}
	/*?}*/

	/** Our own lang keys ({@code automodpack.*}) resolve through {@link L10n} from our jar, never through the game's
	 * language manager: without FAPI's resource loader nothing merges mod lang files into it, and a server-pushed
	 * resource pack must not be able to override our UI text anyway. Vanilla keys stay on vanilla translation.
	 * Embedded components are flattened to their text the way the vanilla formatter would render them.
	 */
	public static MutableComponent text(String key, Object... args) {
		return literal(L10n.get(languageCode(), key, flatten(args)));
	}

	private static Object[] flatten(Object... args) {
		for (int i = 0; i < args.length; i++) {
			if (args[i] instanceof Component component) args[i] = component.getString();
		}
		return args;
	}

	private static String languageCode() {
		if (Constants.LOADER_MANAGER != null && Constants.LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT) {
			/*? if <1.20 {*/
			/*return Minecraft.getInstance().getLanguageManager().getSelected().getCode();
			*//*?} else {*/
			return Minecraft.getInstance().getLanguageManager().getSelected();
			/*?}*/
		}
		// Dedicated server feedback has no client language to honor.
		return "en_us";
	}
}
