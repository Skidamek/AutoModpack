package pl.skidam.automodpack_core.screen;

import java.util.Optional;

public interface ScreenService {

    void download(Object... args);
    void fetch(Object... args);
    void changelog(Object... args);
    void restart(Object... args);
    void danger(Object... args);
    void error(Object... args);
    void menu(Object... args);
    void title(Object... args);

    Optional<String> getScreenString();

    Optional<Object> getScreen();
    Optional<Boolean> properlyLoaded();

}
