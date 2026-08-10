package pl.skidam.automodpack_core.storage;

import java.nio.file.Path;

/** Resolves the game directory supplied to this process by its launcher. */
public final class GameDirectory {
	private static final Path CURRENT = Path.of(System.getProperty("user.dir"));

	private GameDirectory() {}

	public static Path current() {
		return CURRENT;
	}
}
