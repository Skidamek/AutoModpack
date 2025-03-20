package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.callbacks.Callback;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.awt.*;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ReLauncher {

    private final String updateMessage;
    private final Path modpackDir;
    private final UpdateType updateType;
    private final Changelogs changelogs;

    public ReLauncher(UpdateType updateType) {
        this.modpackDir = null;
        this.updateType = updateType;
        this.changelogs = null;
        this.updateMessage = "Successfully updated AutoModpack!";
    }

    public ReLauncher(Path modpackDir, UpdateType updateType, Changelogs changelogs) {
        this.modpackDir = modpackDir;
        this.updateType = updateType;
        this.changelogs = changelogs;
        this.updateMessage = "Successfully updated the modpack!";
    }

    public void restart(boolean shutdownInPreload, Callback... callbacks) {
        if (preload && !shutdownInPreload) {
            runCallbacks(callbacks);
            return;
        }

        boolean isClient = LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT;
        boolean isHeadless = GraphicsEnvironment.isHeadless();

        if (isClient) {
            handleClientRestart(callbacks, isHeadless);
        } else {
            handleServerRestart(callbacks);
        }
    }

    private void handleClientRestart(Callback[] callbacks, boolean isHeadless) {
        if (updateType != null && new ScreenManager().getScreenString().isPresent()) {
            new ScreenManager().restart(modpackDir, updateType, changelogs);
        } else if (preload) {
            Semaphore semaphore = null;
            final Thread shutdownHook = new Thread(() -> runCallbacks(callbacks));
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            if (isHeadless) {
                LOGGER.info("Please restart the game to apply updates!");
            } else {
                semaphore = new Gui().open(updateMessage);
            }

            wait(semaphore); // wait for gui to launch
            runCallbacks(callbacks);
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            wait(semaphore); // wait for user interaction to close the gui
            System.exit(0);
        }

        runCallbacks(callbacks);
    }

    private void wait(Semaphore semaphore) {
        if (semaphore != null) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                LOGGER.error("Failed to acquire semaphore", e);
            }
        }
    }

    private void handleServerRestart(Callback[] callbacks) {
        LOGGER.info("Please restart the server to apply updates!");
        runCallbacks(callbacks);
        System.exit(0);
    }

    private void runCallbacks(Callback[] callbacks) {
        for (Callback callback : callbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
