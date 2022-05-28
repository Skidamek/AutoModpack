package pl.skidam.automodpack;

import net.fabricmc.api.ClientModInitializer;
import pl.skidam.automodpack.utils.InternetConnectionCheck;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.*;
public class AutoModpackClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {

        LOGGER.info("Initializing AutoModpack...");

        InternetConnectionCheck.InternetConnectionCheck();

        CompletableFuture.runAsync(() -> new StartAndCheck(true));
    }
}
