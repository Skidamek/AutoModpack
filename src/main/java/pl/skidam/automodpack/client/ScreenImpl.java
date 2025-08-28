package pl.skidam.automodpack.client;

import pl.skidam.automodpack.client.ui.*;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.ScreenService;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.FetchManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

public class ScreenImpl implements ScreenService {

    @Override
    public void download(Object... args) {
        Screens.download(args[0], args[1]);
    }

    @Override
    public void fetch(Object... args) {
        Screens.fetch(args[0]);
    }

    @Override
    public void changelog(Object... args) {
        Screens.changelog(args[0], args[1], args[2]);
    }

    @Override
    public void restart(Object... args) {
        Screens.restart(args[0], args[1], args[2]);
    }

    @Override
    public void danger(Object... args) {
        Screens.danger(args[0], args[1]);
    }

    @Override
    public void error(String... args) {
        Screens.error(args);
    }

    @Override
    public void menu(Object... args) {
        Screens.menu();
    }

    @Override
    public void title(Object... args) {
        Screens.title();
    }

    @Override
    public void validation(Object... args) {
        Screens.validation(args[0], args[1], args[2], args[3]);
    }

    @Override
    public void downloadselection(Object... args) {
        Screens.downloadselection(args[0], args[1]);
    }

    @Override
    public Optional<String> getScreenString() {
        Screen screen = Screens.getScreen();
        return Optional.of(screen.getTitle().getString().toLowerCase());
    }

    @Override
    public Optional<Object> getScreen() {
        return Optional.ofNullable(Screens.getScreen());
    }

    private static class Screens {
        private static Screen getScreen() {
            return Minecraft.getInstance().screen;
        }

        public static void setScreen(Screen screen) {
            // required for forge to handle it properly
            Util.backgroundExecutor().execute(() -> Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(screen)));
        }

        public static void download(Object downloadManager, Object header) {
            Screens.setScreen(new DownloadScreen((DownloadManager) downloadManager, (String) header));
        }

        public static void fetch(Object fetchManager) {
            Screens.setScreen(new FetchScreen((FetchManager) fetchManager));
        }

        public static void changelog(Object parent, Object modpackDir, Object changelog) {
            Screens.setScreen(new ChangelogScreen((Screen) parent, (Path) modpackDir, (Changelogs) changelog));
        }

        public static void restart(Object modpackDir, Object updateType, Object changelogs) {
            Screens.setScreen(new RestartScreen((Path) modpackDir, (UpdateType) updateType, (Changelogs) changelogs));
        }

        public static void danger(Object parent, Object modpackUpdaterInstance) {
            Screens.setScreen(new DangerScreen((Screen) parent, (ModpackUpdater) modpackUpdaterInstance));
        }

        public static void error(String... errors) {
            Screens.setScreen(new ErrorScreen(errors));
        }

        public static void title() {
            Screens.setScreen(new TitleScreen());
        }

        public static void menu() {
//            Screens.setScreen(new MenuScreen());
        }
        public static void downloadselection(Object parent, Object modpackUpdaterInstance) {
            Screens.setScreen(new DownloadSelectionScreen((VersionedScreen) parent, (ModpackUpdater) modpackUpdaterInstance));
        }

        public static void validation(Object parent, Object serverFingerprint, Object validatedCallback,
                                      Object canceledCallback) {
            Screens.setScreen(new ValidationScreen((Screen) parent, (String) serverFingerprint,
                    (Runnable) validatedCallback, (Runnable) canceledCallback));
        }
    }
}
