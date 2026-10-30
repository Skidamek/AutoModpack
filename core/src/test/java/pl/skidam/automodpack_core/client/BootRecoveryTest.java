package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.BootstrapConfig;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/** Pins the clean-boot contract of the boot ordering: defaults out, and a stale stuck-transaction guard cleared. */
class BootRecoveryTest {

	@Test
	void emptyStorageRecoversToDefaultsAndClearsAStaleGuard(@TempDir Path temp) throws Exception {
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		UpdateRecovery.deferredGuard(storage).evaluateAndRecord("stale");
		Path guardFile = storage.stuckTransactionStateFile();
		assertTrue(Files.exists(guardFile));

		BootRecovery.BootDecision decision = new BootRecovery(storage).recover();

		assertNotNull(decision.clientConfig());
		assertTrue(storage.selectedModpackId().isBlank());
		assertFalse(decision.trustedBootstrapApply());
		assertFalse(decision.bootAsIs());
		assertFalse(Files.exists(guardFile));
	}

	@Test
	void installBootstrapWithoutSecretIsATrustedApply(@TempDir Path temp) throws Exception {
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		ConnectionJsons.KnownHostsBootstrapFields fields = BootstrapConfig.install(AddressHelpers.parseOrigin("play.example.com"), null, "abc1234",
				AddressHelpers.parseEndpoint("downloads.example.com:25564"), ModpackConnectionMode.HTTP, null);
		Files.createDirectories(storage.bootstrapFile().getParent());
		ConfigTools.writeAtomic(storage.bootstrapFile(), fields);

		BootRecovery.BootDecision decision = new BootRecovery(storage).recover();

		assertTrue(decision.trustedBootstrapApply());
		assertEquals("abc1234", storage.selectedModpackId());
		assertFalse(Files.exists(storage.bootstrapFile()));
	}

	@Test
	void pinOnlyBootstrapIsNotATrustedApply(@TempDir Path temp) throws Exception {
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = "play.example.com";
		Files.createDirectories(storage.bootstrapFile().getParent());
		ConfigTools.writeAtomic(storage.bootstrapFile(), fields);

		BootRecovery.BootDecision decision = new BootRecovery(storage).recover();

		assertFalse(decision.trustedBootstrapApply());
		assertTrue(storage.selectedModpackId().isBlank());
	}
}
