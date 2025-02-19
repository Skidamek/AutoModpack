package pl.skidam.automodpack_loader_core.screen;

import java.util.Optional;

public interface ScreenService {

    void downloadselection(Object... args);
    void download(Object... args);
    void fetch(Object... args);
    void changelog(Object... args);
    void restart(Object... args);
    void danger(Object... args);
    void error(String... args);
    void menu(Object... args);
    void title(Object... args);

    Optional<String> getScreenString();

    Optional<Object> getScreen();
}
