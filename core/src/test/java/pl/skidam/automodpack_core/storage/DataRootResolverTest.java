package pl.skidam.automodpack_core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;
import pl.skidam.automodpack_core.update.ClientStorage;

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
		assertSame(ClientStorage.open(game), ClientStorage.open(alias));
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

	@Test
	void unusablePinnedRootHealsByResolvingANewRoot() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("heal-game"));
		Path blocked = temporaryDirectory.resolve("blocked").resolve("data");
		Files.writeString(temporaryDirectory.resolve("blocked"), "not a directory", StandardCharsets.UTF_8);
		writeMarker(game, blocked);
		String home = System.getProperty("user.home");
		System.setProperty("user.home", temporaryDirectory.toString());
		try {
			DataRootResolver.Location location = DataRootResolver.resolve(game);
			assertNotEquals(blocked, location.root(), "unusable pin must not be kept");
			assertTrue(Files.isDirectory(location.root()), "resolver must select a usable root when the pinned one is gone");
		} finally {
			System.setProperty("user.home", home);
		}
	}

	private static void writeMarker(Path game, Path data) throws IOException {
		Files.createDirectory(game.resolve("automodpack"));
		StorageJsons.DataRootFields marker = new StorageJsons.DataRootFields();
		marker.root = data.toString();
		ConfigTools.writeAtomic(game.resolve("automodpack/data-root.json"), marker);
	}
}
