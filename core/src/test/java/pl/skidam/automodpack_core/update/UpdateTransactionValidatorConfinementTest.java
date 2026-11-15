package pl.skidam.automodpack_core.update;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.PlatformUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** The validator's path pass refuses links at a shared directory prefix and at each entry's leaf. */
class UpdateTransactionValidatorConfinementTest {
	@TempDir
	Path tempDir;

	@Test
	void linkedDirectoryPrefixIsRefusedBeforeAnyOperation() throws Exception {
		Fixture fixture = fixture();
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("a.toml"), "outside");
		Path linked = fixture.storage.gameDirectory().resolve("config");
		Files.createSymbolicLink(linked, outside);

		IOException refused = assertThrows(IOException.class, fixture::validate);

		assertEquals("Transaction target contains a symbolic link: " + linked, refused.getMessage());
	}

	@Test
	void leafSymlinkIsRefusedBeforeAnyOperation() throws Exception {
		Fixture fixture = fixture();
		Path config = Files.createDirectories(fixture.storage.gameDirectory().resolve("config"));
		Path elsewhere = Files.writeString(tempDir.resolve("elsewhere.toml"), "elsewhere");
		Path leaf = config.resolve("a.toml");
		Files.createSymbolicLink(leaf, elsewhere);

		IOException refused = assertThrows(IOException.class, fixture::validate);

		assertEquals("Transaction target contains a symbolic link: " + leaf, refused.getMessage());
	}

	/**
	 * ClientStorage canonicalizes the configured game directory, so the validator's GAME_DIR root is the real path and
	 * the root-trust branch is not reachable here. The direct root cases live in FileTreesTest; the planner's player-root
	 * case is the junctioned mods directory in SyncTransitionsTest. This test pins the canonicalization and proves an
	 * inner junction is still refused underneath it.
	 */
	@Test
	void aRelocatedGameDirectoryIsCanonicalizedAndItsInnerJunctionStillRefused() throws Exception {
		Assumptions.assumeTrue(PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS, "junctions are a Windows reparse-point feature");
		Path real = Files.createDirectories(tempDir.resolve("real-game"));
		Path linked = tempDir.resolve("linked-game");
		assertEquals(0, mklinkJunction(linked, real));
		ClientStorage storage = TestDataRoot.open(linked, tempDir.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		assertEquals(linked.toRealPath(), storage.gameDirectory(), "ClientStorage resolves a relocated game directory before it is used as a root");

		assertDoesNotThrow(fixture(storage)::validate);

		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("a.toml"), "outside");
		assertEquals(0, mklinkJunction(real.resolve("config"), outside));
		IOException refused = assertThrows(IOException.class, fixture(storage)::validate);
		assertEquals("Transaction target contains a symbolic link: " + storage.gameDirectory().resolve("config"), refused.getMessage());
	}

	private Fixture fixture() throws Exception {
		return fixture(UpdateTestFixtures.storage(tempDir));
	}

	private Fixture fixture(ClientStorage storage) throws Exception {
		byte[] content = "a".getBytes(UTF_8);
		String hash = UpdateTestFixtures.store(storage, content);
		SelectedModpackTarget target = UpdateTestFixtures.target(storage, "config/a.toml", "config", false, hash, content.length);
		UpdatePlan plan = UpdateTestFixtures.plan(target, List.of(new Operation(Root.GAME_DIR, "config/a.toml", OperationType.INSTALL_OBJECT, hash, content.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "config/a.toml", true, hash, content.length),
						new ProjectedFile(Root.GAME_DIR, "config/a.toml", true, hash, content.length)));
		return new Fixture(storage, target, UpdateTestFixtures.createTransaction(storage, plan, target));
	}

	private record Fixture(ClientStorage storage, SelectedModpackTarget target, UpdateTransaction transaction) {
		void validate() throws IOException {
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				new UpdateTransactionValidator(storage).validate(transaction, target, true, cache);
			}
		}
	}

	private static int mklinkJunction(Path link, Path target) throws Exception {
		Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
		process.getInputStream().readAllBytes();
		return process.waitFor();
	}
}
