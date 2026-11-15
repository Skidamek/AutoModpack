package pl.skidam.automodpack_core.update;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * One commit execution carries one directory proof per managed root, and a removal drops that root's proof before the
 * next operation resolves: a directory a removal may have pruned or replaced cannot be resolved through a stale proof.
 */
class UpdateCommitConfinementTest {
	@TempDir
	Path tempDir;

	@Test
	void aDirectorySwappedAfterARemovalIsRefusedByTheNextOperation() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(tempDir);
		byte[] shipped = "shipped".getBytes(UTF_8);
		String shippedHash = UpdateTestFixtures.store(storage, shipped);
		SelectedModpackTarget target = UpdateTestFixtures.target(storage, "mods/new.jar", "mods", false, shippedHash, shipped.length);
		byte[] first = "first".getBytes(UTF_8);
		byte[] second = "second".getBytes(UTF_8);
		Path config = Files.createDirectories(storage.gameDirectory().resolve("config"));
		Files.write(config.resolve("a.toml"), first);
		Files.write(config.resolve("b.toml"), second);
		UpdatePlan plan = UpdateTestFixtures.plan(target,
				List.of(new Operation(Root.PROJECTION, "mods/new.jar", OperationType.INSTALL_OBJECT, shippedHash, shipped.length, null),
						new Operation(Root.GAME_DIR, "config/a.toml", OperationType.DELETE, null, -1, HashUtils.sha1(first)),
						new Operation(Root.GAME_DIR, "config/b.toml", OperationType.DELETE, null, -1, HashUtils.sha1(second))).stream().sorted(Operation.ORDER).toList(),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/new.jar", true, shippedHash, shipped.length), new ProjectedFile(Root.GAME_DIR, "config/a.toml", false, null, -1),
						new ProjectedFile(Root.GAME_DIR, "config/b.toml", false, null, -1)));
		UpdateTransaction transaction = UpdateTestFixtures.createTransaction(storage, plan, target);
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		UpdateCommit.Probe swapAfterFirstRemoval = new UpdateCommit.Probe() {
			@Override
			public void afterRemoval(Path removed) throws IOException {
				if (!removed.getFileName().toString().equals("a.toml")) return;
				Files.move(config, tempDir.resolve("moved-config"));
				Files.createSymbolicLink(config, outside);
			}
		};

		IOException refused = assertThrows(IOException.class,
				() -> new UpdateCommit(storage, swapAfterFirstRemoval).run(new UpdateCommit.Fresh(() -> new UpdateCommit.Built(transaction, target), null)));

		assertTrue(refused.getMessage().contains("contains a symbolic link: " + config), refused.getMessage());
		assertTrue(Files.isSymbolicLink(config), "the test swapped the directory after the first removal");
		assertFalse(Files.exists(outside.resolve("b.toml")), "the second operation never reached the linked directory");
	}
}
