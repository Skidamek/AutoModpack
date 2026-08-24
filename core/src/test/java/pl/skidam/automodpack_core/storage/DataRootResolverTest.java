package pl.skidam.automodpack_core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
