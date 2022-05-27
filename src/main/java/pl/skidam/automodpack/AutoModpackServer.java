package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;

public class AutoModpackServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        System.out.println("Welcome to AutoModpack on Server!");

        // TODO generate files for the server
        // TODO add chad integration to the server who when you join the server, it will download the mods and update the mods by ping the server -- networking
        // TODO kick player if they don't have the AutoModpack

    }
}
