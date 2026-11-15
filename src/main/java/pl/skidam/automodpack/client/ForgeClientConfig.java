package pl.skidam.automodpack.client;

/*? if forge {*/
/*import net.minecraftforge.fml.ModLoadingContext;

import pl.skidam.automodpack.client.ui.screen.InstalledModpacksScreen;

/^? if <1.19 {^/
/^import net.minecraftforge.client.ConfigGuiHandler;
^//^?} else {^/
import net.minecraftforge.client.ConfigScreenHandler;
/^?}^/

public final class ForgeClientConfig {
	private ForgeClientConfig() {}

	public static void register() {
		/^? if <1.19 {^/
		/^ModLoadingContext.get().registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class, () -> new ConfigGuiHandler.ConfigGuiFactory(parent -> new InstalledModpacksScreen(parent)));
		^//^?} else {^/
		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, () -> new ConfigScreenHandler.ConfigScreenFactory((client, parent) -> new InstalledModpacksScreen(parent)));
		/^?}^/
	}
}
*//*?}*/
