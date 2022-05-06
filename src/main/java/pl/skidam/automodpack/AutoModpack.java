package pl.skidam.automodpack;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.skidam.automodpack.modpack.Modpack;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

public class AutoModpack implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");

//    public static final Identifier TEXTURE = new Identifier("automodpack", "textures/gui/icon.png");

    @Override
    public void onInitialize() {

        LOGGER.info("Initializing AutoModpack...");

        new Thread(new OldConvertToNew()).start();

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

        // Check if AutoModpack Path folder exists and create it if it doesn't
        File AutoModpackPath = new File("./AutoModpack/");
        if (!AutoModpackPath.exists()) {
            AutoModpackPath.mkdir();
        }

        new Thread(new SelfUpdater(10000)).start();
        new Thread(new Modpack(10000)).start();


        // TODO add chad integration to the server who when you join the server, it will download the mods and update the mods by ping the server

    }
}
