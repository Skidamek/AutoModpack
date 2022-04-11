package pl.skidam.automodpack;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class AutoModpack implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("modid");

    @Override
    public void onInitialize() {

        LOGGER.info("Hello Fabric world!");

//        new Thread(new CurrentMods()).start();
//
//        new Thread(new LatestMods()).start();


        String link = "http://130.61.233.54/download/kloce.zip";

        File out = new File( "./mods/AutoModpack.zip");

        new Thread(new Download(link, out)).start();

    }
}
