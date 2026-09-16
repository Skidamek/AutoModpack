package pl.skidam.automodpack_core.storage;

import java.nio.file.Files;
import java.nio.file.Path;

import pl.skidam.automodpack_core.update.ClientStorage;

/** Pins {@link ClientStorage} to a temp data root so tests never touch the platform CAS. */
public final class TestDataRoot {
	private TestDataRoot() {}

	public static ClientStorage open(Path gameDirectory, Path dataDirectory) throws Exception {
		Files.createDirectories(dataDirectory);
		String previous = System.setProperty(StoragePaths.DATA_ROOT_PROPERTY, dataDirectory.toAbsolutePath().normalize().toString());
		try {
			return ClientStorage.open(gameDirectory);
		} finally {
			if (previous == null) System.clearProperty(StoragePaths.DATA_ROOT_PROPERTY);
			else System.setProperty(StoragePaths.DATA_ROOT_PROPERTY, previous);
		}
	}
}
