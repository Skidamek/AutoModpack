package pl.skidam.automodpack.client;

/*? if fabric {*/
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import pl.skidam.automodpack.client.ui.screen.InstalledModpacksScreen;

/** Mod Menu's config entry opens the installed-packs hub. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new InstalledModpacksScreen(parent);
	}
}
/*?}*/
