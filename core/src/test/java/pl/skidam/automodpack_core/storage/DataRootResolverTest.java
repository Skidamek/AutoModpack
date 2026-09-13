package pl.skidam.automodpack_core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.update.ClientStorage;

class DataRootResolverTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void canonicalPathAliasesKeepOneOwnerIdentity() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("game"));
		Path data = Files.createDirectory(temporaryDirectory.resolve("data"));
		ClientStorage canonicalStorage = TestDataRoot.open(game, data);
		Path alias = temporaryDirectory.resolve("game-alias");
		try {
			Files.createSymbolicLink(alias, game);
		} catch (IOException | UnsupportedOperationException e) {
			assumeTrue(false, "Symbolic links are unavailable");
		}

		DataRootResolver.Location canonical = canonicalStorage.dataLocation();
		DataRootResolver.Location throughAlias = TestDataRoot.open(alias, data).dataLocation();

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
	void configuredRootOverridesSharedAndLocal() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("configured-game"));
		Path data = Files.createDirectory(temporaryDirectory.resolve("configured-data"));
		ClientStorage storage = TestDataRoot.open(game, data);
		assertEquals(data.toAbsolutePath().normalize(), storage.dataDirectory());
	}

	@Test
	void differentInstallationsGetDifferentOwnerIdentities() throws Exception {
		Path data = Files.createDirectory(temporaryDirectory.resolve("shared-data"));
		ClientStorage original = TestDataRoot.open(temporaryDirectory.resolve("original-game"), data);
		ClientStorage clone = TestDataRoot.open(temporaryDirectory.resolve("cloned-game"), data);
		assertNotEquals(original.dataLocation().ownerId(), clone.dataLocation().ownerId());
		assertEquals(original.dataDirectory(), clone.dataDirectory());
	}

	@Test
	void shardsObjectFilesOnTheFirstTwoHexCharacters() {
		Path objects = Path.of("objects");
		Path file = DataRootResolver.objectFile(objects, "0123456789abcdef0123456789abcdef01234567");
		assertEquals(Path.of("objects/01/23456789abcdef0123456789abcdef01234567").toAbsolutePath().normalize(), file);
		assertEquals("0123456789abcdef0123456789abcdef01234567", DataRootResolver.objectHash(objects, file));
		assertTrue(DataRootResolver.isObjectFile(objects, file));
		assertTrue(!DataRootResolver.isObjectFile(objects, objects.resolve("0123456789abcdef0123456789abcdef01234567")));
	}

	@Test
	void dedicatedServersOwnTheirCacheInsideTheServerScope() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("server-game"));
		DataRootResolver.Location location = DataRootResolver.resolve(game, LoaderManagerService.EnvironmentType.SERVER);
		// The resolver canonicalizes the game root; on Windows the injected temp path may spell it as an 8.3 alias, so compare real paths.
		assertEquals(game.resolve("automodpack").resolve("server").resolve("data").toRealPath(), location.root().toRealPath());
		assertTrue(location.root().toRealPath().startsWith(game.toRealPath()));
	}

	@Test
	void configuredRootWinsForDedicatedServersToo() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("server-configured-game"));
		Path data = Files.createDirectory(temporaryDirectory.resolve("server-configured-data"));
		String previous = System.setProperty(StoragePaths.DATA_ROOT_PROPERTY, data.toAbsolutePath().normalize().toString());
		try {
			assertEquals(data.toAbsolutePath().normalize(), DataRootResolver.resolve(game, LoaderManagerService.EnvironmentType.SERVER).root());
		} finally {
			if (previous == null) System.clearProperty(StoragePaths.DATA_ROOT_PROPERTY);
			else System.setProperty(StoragePaths.DATA_ROOT_PROPERTY, previous);
		}
	}

	@Test
	void clientUnknownAndUniversalProcessesShareThePlatformRoot() throws Exception {
		// The platform root is only predictable when neither environment variable pins it; otherwise the test would
		// reach into a real user directory.
		assumeTrue(System.getenv("XDG_DATA_HOME") == null && System.getenv("LOCALAPPDATA") == null, "The platform data root is pinned by the environment");
		String previousHome = System.setProperty("user.home", temporaryDirectory.resolve("home").toString());
		try {
			Path game = Files.createDirectory(temporaryDirectory.resolve("client-game"));
			DataRootResolver.Location client = DataRootResolver.resolve(game, LoaderManagerService.EnvironmentType.CLIENT);
			assertEquals(DataRootResolver.resolve(game, LoaderManagerService.EnvironmentType.UNIVERSAL).root(), client.root());
			assertEquals(DataRootResolver.resolve(game, null).root(), client.root());
			assertFalse(client.root().startsWith(game));
			assertNotEquals(game.resolve("automodpack").resolve("server").resolve("data").toAbsolutePath().normalize(), client.root());
		} finally {
			if (previousHome == null) System.clearProperty("user.home");
			else System.setProperty("user.home", previousHome);
		}
	}
}
