package pl.skidam.automodpack.utils;

import me.shedaniel.autoconfig.AutoConfig;
import pl.skidam.automodpack.config.AutoModpackConfig;

public class Others {
    public static AutoModpackConfig config = AutoConfig.getConfigHolder(AutoModpackConfig.class).getConfig();
}
