package pl.skidam.automodpack;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

public class AutoModpack implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");

    @Override
    public void onInitialize() {

        Thread.currentThread().setName("AutoModpack");
        Thread.currentThread().setPriority(10);

        LOGGER.info("Hello Fabric world!");

        // Internet connection check
        boolean internet = false;
        while (!internet) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("https://www.google.com").openConnection();
                connection.setRequestMethod("HEAD");
                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new Exception("AutoModpack -- Internet isn't available, Failed to get code 200 from " + connection.getURL().toString());
                }
                else {
                    internet = true;
                    System.out.println("AutoModpack -- Internet is available");
                }
            } catch (Exception e) {
                System.err.println("AutoModpack -- Make sure that you have an internet connection!");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        // Check if AutoModpack Downloads folder exists and create it if it doesn't
        File downloads = new File("./mods/downloads/");
        if (!downloads.exists()) {
            downloads.mkdir();
        }


        // TODO check if AutoModpack is on latest version if not download latest version.
        String selfLink = "https://github.com/Skidamek/AutoModpack/releases/download/pipel/AutoModpack.jar";
        File selfOut = new File( "./mods/AutoModpack.jar");
        new Thread(new SelfUpdater(selfLink, selfOut)).start();



        // TODO if latestmods is not same as currentmods download new mods.
        String link = "http://130.61.177.253/download/modpack.zip";
        File out = new File("./mods/downloads/AutoModpack.zip");
        new Thread(new Download(link, out)).start();


    }
}
