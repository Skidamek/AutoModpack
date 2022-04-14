package pl.skidam.automodpack;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class AutoModpack implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");

    @Override
    public void onInitialize() {

        LOGGER.info("Hello Fabric world!1!!");

        // TODO check internet connection while internet isn't available, wait. 2

//        checkInternetAccess();

        // TODO check if AutoModpack is on latest version if not download latest version. 1

//        new Thread(new SelfUpdater()).start();

        // TODO check what mods are installed. 3

//        new Thread(new CurrentMods()).start();

        // TODO check what are latestmods. 4

//        new Thread(new LatestMods()).start();

        // TODO if latestmods is not same as currentmods download new mods. 5

        String link = "http://130.61.233.54/download/kloce.zip";
        File out = new File( "./mods/AutoModpack.zip");
        new Thread(new Download(link, out)).start();

    }
}
