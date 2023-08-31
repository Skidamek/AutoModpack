package pl.skidam.automodpack_core.screen;

import java.util.Optional;
import java.util.ServiceLoader;

public class ScreenManager implements ScreenService {
    private Optional<ScreenService> getServiceLoader() {
        ServiceLoader<ScreenService> loaderServiceLoader = ServiceLoader.load(ScreenService.class);
        for (ScreenService loaderService : loaderServiceLoader) {
            return Optional.of(loaderService);
        }

        // no service loader found, just ignore
        // this should mean that we are in not fully game loaded env
        return Optional.empty();
    }

    @Override
    public void download(Object... args) {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return;
        service.get().download(args);
    }

    @Override
    public void fetch(Object... args) {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return;
        service.get().fetch(args);
    }

    @Override
    public void changelog(Object... args) {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return;
        service.get().changelog(args);
    }

    @Override
    public void restart(Object... args) {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return;
        service.get().restart(args);
    }

    @Override
    public void danger(Object... args) {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return;
        service.get().danger(args);
    }

    @Override
    public void error(Object... args) {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return;
        service.get().error(args);
    }

    @Override
    public void menu(Object... args) {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return;
        service.get().menu(args);
    }

    @Override
    public void title(Object... args) {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return;
        service.get().title(args);
    }

    @Override
    public Optional<String> getScreenString() {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return Optional.empty();
        return service.get().getScreenString();
    }

    @Override
    public Optional<Object> getScreen() {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return Optional.empty();
        return service.get().getScreen();
    }

    @Override
    public Optional<Boolean> properlyLoaded() {
        Optional<ScreenService> service = getServiceLoader();
        if (service.isEmpty()) return Optional.empty();
        return service.get().properlyLoaded();
    }
}
