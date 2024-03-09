package pl.skidam.automodpack_loader_core.screen;

import java.util.Optional;

public class ScreenManager implements ScreenService {

    public static ScreenService INSTANCE = new PreloadScreenImpl();

    @Override
    public void download(Object... args) {
        INSTANCE.download(args);
    }

    @Override
    public void fetch(Object... args) {
        INSTANCE.fetch(args);
    }

    @Override
    public void changelog(Object... args) {
        INSTANCE.changelog(args);
    }

    @Override
    public void restart(Object... args) {
        INSTANCE.restart(args);
    }

    @Override
    public void danger(Object... args) {
        INSTANCE.danger(args);
    }

    @Override
    public void error(String... args) {
        INSTANCE.error(args);
    }

    @Override
    public void menu(Object... args) {
        INSTANCE.menu(args);
    }

    @Override
    public void title(Object... args) {
        INSTANCE.title(args);
    }

    @Override
    public Optional<String> getScreenString() {
        return INSTANCE.getScreenString();
    }

    @Override
    public Optional<Object> getScreen() {
        return INSTANCE.getScreen();
    }
}
