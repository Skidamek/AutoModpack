package pl.skidam.automodpack.init;

import pl.skidam.automodpack_core.modpack.Modpack;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.loader.LoaderService;

import java.io.IOException;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.GlobalVariables.httpServer;

public class CommonInit {

    public static void afterSetupServer() {
        if (new LoaderManager().getEnvironmentType() != LoaderService.EnvironmentType.SERVER) {
            return;
        }

        try {
            httpServer.start();
        } catch (IOException e) {
            LOGGER.error("Couldn't start server.", e);
        }
    }

    public static void beforeShutdownServer() {
        if (new LoaderManager().getEnvironmentType() != LoaderService.EnvironmentType.SERVER) {
            return;
        }

        httpServer.stop();
        Modpack.shutdownExecutor();
    }
}
