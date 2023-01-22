package pl.skidam.automodpack;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class CompatCheck {
    static final Logger LOGGER = LogUtils.getLogger();

    public CompatCheck() {
        AutoModpack.isClothConfig = Platform.isModLoaded("cloth-config");
        AutoModpack.isModMenu = Platform.isModLoaded("modmenu");

        AutoModpack.isVelocity = Platform.isModLoaded("fabricproxy-lite") || Platform.isModLoaded("crossstitch");

//        if (Platform.isModLoaded("seamless_loading_screen")) { it shouldn't be incompatible anymore
//            String jarName = JarUtilities.getJarFileOfMod("seamless_loading_screen");
//            LOGGER.error("Found incompatibility between AutoModpack and Seamless Loading Screen. Delete {} mod to have better experience!", jarName);
//        }
        Download();
    }

    private void Download() {
        Platform.downloadDependencies();
    }
}
