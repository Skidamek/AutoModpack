package pl.skidam.automodpack_loader_velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import pl.skidam.automodpack_core.modpack.Modpack;
import pl.skidam.automodpack_core.netty.HttpServer;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_velocity.loader.VelocityLoaderManager;

import static pl.skidam.automodpack_core.GlobalVariables.*;

@Plugin(id = "automodpack", name = "AutoModpack", version = "4.0.0", authors = {"Skidam"})
public class HelloVelo {

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public HelloVelo(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;

        LOADER_MANAGER = new VelocityLoaderManager();

        new Preload();
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {

        preload = false;

        long start = System.currentTimeMillis();
        LOGGER.info("Launching AutoModpack...");

        httpServer = new HttpServer();
        modpack = new Modpack();

        if (serverConfig.generateModpackOnStart) {
            LOGGER.info("Generating modpack...");
            long genStart = System.currentTimeMillis();
            if (modpack.generateNew()) {
                LOGGER.info("Modpack generated! took " + (System.currentTimeMillis() - genStart) + "ms");
            } else {
                LOGGER.error("Failed to generate modpack!");
            }
        } else {
            LOGGER.info("Loading last modpack...");
            long genStart = System.currentTimeMillis();
            if (modpack.loadLast()) {
                LOGGER.info("Modpack loaded! took " + (System.currentTimeMillis() - genStart) + "ms");
            } else {
                LOGGER.error("Failed to load modpack!");
            }
        }

        // TODO: Login mod packets!
//        ModPackets.registerS2CPackets();

        // TODO: Make these commands actually do something
        CommandManager commandManager = proxy.getCommandManager();
        // Here you can add meta for the command, as aliases and the plugin to which it belongs (RECOMMENDED)
        CommandMeta commandMeta = commandManager.metaBuilder("automodpack")
                // This will create a new alias for the command "/test"
                // with the same arguments and functionality
                .plugin(this)
                .build();

        // You can replace this with "new EchoCommand()" or "new TestCommand()"
        // SimpleCommand simpleCommand = new TestCommand();
        // RawCommand rawCommand = new EchoCommand();
        // The registration is done in the same way, since all 3 interfaces implement "Command"
        BrigadierCommand commandToRegister = Commands.register(proxy);

        // Finally, you can register the command
        commandManager.register(commandMeta, commandToRegister);


        LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");

    }
}
