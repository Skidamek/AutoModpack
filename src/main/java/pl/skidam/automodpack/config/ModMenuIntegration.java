package pl.skidam.automodpack.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.utils.ToastExecutor;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return (parent) -> {
            if (AutoModpackMain.isClothConfig) {
                return ConfigScreen.createConfigGui(new Config(), parent);
            }
            new ToastExecutor(6);
            return parent;
        };
    }
}
