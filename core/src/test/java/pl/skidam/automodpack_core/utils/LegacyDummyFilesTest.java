package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransaction.LegacyDummyTarget;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;

class LegacyDummyFilesTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void matchesOnlyTheExactHistoricalDummyJar() throws Exception {
		byte[] signature = LegacyDummyFiles.signatureForTesting();
		assertEquals(LegacyDummyFiles.SIZE, signature.length);
		Path exact = Files.write(temporaryDirectory.resolve("exact.jar"), signature);
		assertTrue(LegacyDummyFiles.matches(exact));
		assertEquals(LegacyDummyFiles.SHA1, HashUtils.getHash(exact));

		signature[signature.length - 1] ^= 1;
		assertFalse(LegacyDummyFiles.matches(Files.write(temporaryDirectory.resolve("changed.jar"), signature)));
		assertFalse(LegacyDummyFiles.matches(Files.write(temporaryDirectory.resolve("prefix.jar"), new byte[]{80, 75, 3, 4})));
	}

	@Test
	void cleanupTransactionDeletesVerifiedRemnantAndPrunesRegistry() throws Exception {
		Path game = temporaryDirectory.resolve("game");
		Path automodpack = game.resolve("automodpack");
		Path mods = game.resolve("mods");
		Path store = automodpack.resolve("store");
		Path privateDirectory = automodpack.resolve(".private");
		Files.createDirectories(mods);
		Files.createDirectories(store);
		Path dummy = Files.write(mods.resolve("legacy.jar"), LegacyDummyFiles.signatureForTesting());
		Path registryPath = automodpack.resolve("automodpack-dummy-files.json");
		Jsons.ClientDummyFiles registry = new Jsons.ClientDummyFiles();
		registry.files.add(dummy.toAbsolutePath().normalize().toString());
		ConfigTools.writeAtomic(registryPath, registry);

		UpdateTransaction transaction = UpdateTransaction.createLegacyDummyCleanup(List.of(new LegacyDummyTarget(Root.MODS_DIR, "legacy.jar")));
		UpdateTransactionExecutor executor = new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(game, null, mods, store, automodpack,
				privateDirectory.resolve("update-transaction.json"), privateDirectory.resolve("update-transaction-result.json"),
				automodpack.resolve("automodpack-client.json"), automodpack.resolve("automodpack-deletion-timestamps-files.json"), null, null));

		assertTrue(executor.commit(transaction).success());
		assertFalse(Files.exists(dummy));
		assertFalse(Files.exists(registryPath));
		assertFalse(Files.exists(privateDirectory.resolve("update-transaction.json")));
	}
}
