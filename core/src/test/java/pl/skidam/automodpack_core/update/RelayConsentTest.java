package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.TestDataRoot;

class RelayConsentTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void packsAreNotSharedUntilThePlayerSaysSo() throws Exception {
		ClientStorage storage = TestDataRoot.open(Files.createDirectory(temporaryDirectory.resolve("game")), Files.createDirectory(temporaryDirectory.resolve("data")));
		assertFalse(RelayConsent.isRelayed(storage, "pack111"));
		assertFalse(Files.exists(storage.relayConsentFile()));

		RelayConsent.setRelayed(storage, "pack111", true);
		assertTrue(RelayConsent.isRelayed(storage, "pack111"));
		assertFalse(RelayConsent.isRelayed(storage, "pack222"));

		RelayConsent.setRelayed(storage, "pack111", false);
		assertFalse(RelayConsent.isRelayed(storage, "pack111"));
	}

	@Test
	void forgettingAPackWithdrawsItsSharing() throws Exception {
		ClientStorage storage = TestDataRoot.open(Files.createDirectory(temporaryDirectory.resolve("game")), Files.createDirectory(temporaryDirectory.resolve("data")));
		RelayConsent.setRelayed(storage, "pack111", true);
		new ClientGenerationStore(storage).forgetModpack("pack111");
		assertFalse(RelayConsent.isRelayed(storage, "pack111"));
	}

	@Test
	void anUnreadableConsentFileSharesNothing() throws Exception {
		ClientStorage storage = TestDataRoot.open(Files.createDirectory(temporaryDirectory.resolve("game")), Files.createDirectory(temporaryDirectory.resolve("data")));
		Files.createDirectories(storage.relayConsentFile().getParent());
		Files.writeString(storage.relayConsentFile(), "{\"DO_NOT_CHANGE_IT\": 7, \"modpackIds\": [\"pack111\"]}");
		assertFalse(RelayConsent.isRelayed(storage, "pack111"));
	}
}
