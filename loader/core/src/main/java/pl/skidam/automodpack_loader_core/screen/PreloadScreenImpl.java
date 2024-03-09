package pl.skidam.automodpack_loader_core.screen;

import java.util.Optional;

public class PreloadScreenImpl implements ScreenService {

    // We leave this all empty
    @Override
    public void download(Object... args) { }

    @Override
    public void fetch(Object... args) { }

    @Override
    public void changelog(Object... args) { }

    @Override
    public void restart(Object... args) { }

    @Override
    public void danger(Object... args) { }

    @Override
    public void error(String... args) { }

    @Override
    public void menu(Object... args) { }

    @Override
    public void title(Object... args) { }

    @Override
    public Optional<String> getScreenString() {
        return Optional.empty();
    }

    @Override
    public Optional<Object> getScreen() {
        return Optional.empty();
    }
}
