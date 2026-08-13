package pl.skidam.automodpack_core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;

class DataRootResolverTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void canonicalPathAliasesKeepOneOwnerIdentity() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("game"));
		Path data = Files.createDirectory(temporaryDirectory.resolve("data"));
		writeMarker(game, data);
		Path alias = temporaryDirectory.resolve("game-alias");
		try {
			Files.createSymbolicLink(alias, game);
		} catch (IOException | UnsupportedOperationException e) {
			assumeTrue(false, "Symbolic links are unavailable");
		}

		DataRootResolver.Location canonical = DataRootResolver.resolve(game);
		DataRootResolver.Location throughAlias = DataRootResolver.resolve(alias);

		assertEquals(canonical.ownerId(), throughAlias.ownerId());
		assertEquals(game.toRealPath(), throughAlias.ownerPath());
	}

	@Test
	void rejectsRedirectedLocalDataDirectory() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("redirect-game"));
		Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
		try {
			Files.createSymbolicLink(game.resolve("automodpack"), outside);
		} catch (IOException | UnsupportedOperationException e) {
			assumeTrue(false, "Symbolic links are unavailable");
		}

		assertThrows(IllegalStateException.class, () -> DataRootResolver.resolve(game));
	}

	private static void writeMarker(Path game, Path data) throws IOException {
		Files.createDirectory(game.resolve("automodpack"));
		StorageJsons.DataRootFields marker = new StorageJsons.DataRootFields();
		marker.root = data.toString();
		marker.shared = true;
		ConfigTools.writeAtomic(game.resolve("automodpack/data-root.json"), marker);
	}
}
