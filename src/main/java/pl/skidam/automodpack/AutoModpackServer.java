package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;

public class AutoModpackServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        AutoModpackClient.LOGGER.info("Welcome to AutoModpack on Server!");
    }
}
