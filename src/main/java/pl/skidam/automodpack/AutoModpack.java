package pl.skidam.automodpack;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.skidam.automodpack.modpack.Modpack;

import java.net.HttpURLConnection;
import java.net.URL;

public class AutoModpack implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");
    public static String AutoModpackUpdated;
    public static String ModpackUpdated;
    public static boolean Checking;

    @Override
    public void onInitialize() {

        LOGGER.info("Initializing AutoModpack...");

        // Internet connection check
        while (true) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("https://www.google.com").openConnection();
                connection.setRequestMethod("HEAD");
                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new Exception("AutoModpack -- Internet isn't available, Failed to get code 200 from " + connection.getURL().toString());
                }
                else {
                    break;
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


        new Thread(new SelfUpdater(5000)).start();
        new Thread(new Modpack(5000)).start();

        // TODO clean up this trash code!!!!
        // TODO add chad integration to the server who when you join the server, it will download the mods and update the mods by ping the server


        new Thread(new FinishCheck()).start();

    }
}
