package pl.skidam.automodpack.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return (parent) -> {
            if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
                return ConfigScreen.createConfigGui(new Config(), parent);
            }
            return parent;
        };
    }
}
