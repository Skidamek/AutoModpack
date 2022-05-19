package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;

public class AutoModpackServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        System.out.println("Welcome to AutoModpack on Server!");
    }
}
