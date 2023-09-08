package pl.skidam.automodpack_core.screen;

import java.util.Optional;

public class ScreenManager implements ScreenService {

    public static ScreenService screenImpl = new PreloadScreenImpl();

    @Override
    public void download(Object... args) {
        screenImpl.download(args);
    }

    @Override
    public void fetch(Object... args) {
        screenImpl.fetch(args);
    }

    @Override
    public void changelog(Object... args) {
        screenImpl.changelog(args);
    }

    @Override
    public void restart(Object... args) {
        screenImpl.restart(args);
    }

    @Override
    public void danger(Object... args) {
        screenImpl.danger(args);
    }

    @Override
    public void error(Object... args) {
        screenImpl.error(args);
    }

    @Override
    public void menu(Object... args) {
        screenImpl.menu(args);
    }

    @Override
    public void title(Object... args) {
        screenImpl.title(args);
    }

    @Override
    public Optional<String> getScreenString() {
        return screenImpl.getScreenString();
    }

    @Override
    public Optional<Object> getScreen() {
        return screenImpl.getScreen();
    }
}
