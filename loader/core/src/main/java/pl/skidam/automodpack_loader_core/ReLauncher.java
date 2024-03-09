package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.loader.LoaderService;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.nio.file.Path;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ReLauncher {

    private static final String genericMessage = "Successfully applied the modpack!";

    public static class Restart {

        public Restart() {
            new Restart(null, genericMessage, null, null);
        }

        public Restart(String guiMessage) {
            new Restart(null, guiMessage, null, null);
        }

        public Restart(UpdateType updateType) {
            new Restart(null, genericMessage, updateType, null);
        }

        public Restart(String guiMessage, UpdateType updateType) {
            new Restart(null, guiMessage, updateType, null);
        }

        public Restart(UpdateType updateType, Changelogs changelogs) {
            new Restart(null, genericMessage, updateType, changelogs);
        }

        public Restart(Path modpackDir, UpdateType updateType) {
            new Restart(modpackDir, genericMessage, updateType, null);
        }

        public Restart(Path modpackDir, UpdateType updateType, Changelogs changelogs) {
            new Restart(modpackDir, genericMessage, updateType, changelogs);
        }

        public Restart(Path modpackDir, String guiMessage, UpdateType updateType, Changelogs changelogs) {
            boolean isClient = new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.CLIENT;

            if (isClient) {
                if (!preload && updateType != null) {
                    if (new ScreenManager().getScreenString().isPresent() && !new ScreenManager().getScreenString().get().toLowerCase().contains("restartscreen")) {
                        new ScreenManager().restart(modpackDir, updateType, changelogs);
                        return;
                    }
                }

                if (preload) {
                    return;
                }

                LOGGER.info("Restart your client!");
            } else {
                LOGGER.info("Please restart the server to apply updates!");
            }

            System.exit(0);
        }
    }
}
