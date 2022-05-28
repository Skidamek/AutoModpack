package pl.skidam.automodpack;

import net.fabricmc.api.ClientModInitializer;
import pl.skidam.automodpack.utils.InternetConnectionCheck;

import static pl.skidam.automodpack.AutoModpackMain.*;
public class AutoModpackClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {

        LOGGER.info("Initializing AutoModpack...");

        InternetConnectionCheck.InternetConnectionCheck();

        // TODO clean up this trash code!!!!
        // TODO add chad integration to the server who when you join the server, it will download the mods and update the mods by ping the server

        new Thread(() -> new StartAndCheck(true)).start();
    }
}
