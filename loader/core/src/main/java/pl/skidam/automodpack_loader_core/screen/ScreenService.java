package pl.skidam.automodpack_loader_core.screen;

import java.util.Optional;

public interface ScreenService {

	void download(Object... args);

	void changelog(Object... args);

	void restart(Object... args);

	void welcome(Object... args);

	boolean preview(Object... args);

	void recovery(Object... args);

	void history(Object... args);

	void error(String... args);

	void menu(Object... args);

	void title(Object... args);

	void validation(Object... args);

	void waiting();

	Optional<String> getScreenString();

	Optional<Object> getScreen();
}
