package pl.skidam.automodpack_core.screen;

import java.util.Optional;

public class PreloadScreenImpl implements ScreenService {

    // We leave this all empty

    @Override
    public void download(Object... args) {
        return;
    }

    @Override
    public void fetch(Object... args) {
        return;
    }

    @Override
    public void changelog(Object... args) {
        return;
    }

    @Override
    public void restart(Object... args) {
        return;
    }

    @Override
    public void danger(Object... args) {
        return;
    }

    @Override
    public void error(Object... args) {
        return;
    }

    @Override
    public void menu(Object... args) {
        return;
    }

    @Override
    public void title(Object... args) {
        return;
    }

    @Override
    public Optional<String> getScreenString() {
        return Optional.empty();
    }

    @Override
    public Optional<Object> getScreen() {
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> properlyLoaded() {
        return Optional.empty();
    }
}
