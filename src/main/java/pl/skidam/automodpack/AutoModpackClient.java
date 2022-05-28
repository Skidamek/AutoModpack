package pl.skidam.automodpack;

import net.fabricmc.api.ClientModInitializer;
import pl.skidam.automodpack.utils.InternetConnectionCheck;

import static pl.skidam.automodpack.AutoModpackMain.*;
public class AutoModpackClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {

        LOGGER.info("Initializing AutoModpack...");

        InternetConnectionCheck.InternetConnectionCheck();

        new Thread(() -> new StartAndCheck(true)).start();
    }
}
