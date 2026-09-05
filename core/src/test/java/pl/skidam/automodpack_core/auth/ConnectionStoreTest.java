package pl.skidam.automodpack_core.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;

class ConnectionStoreTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void approvalsSurviveAReconnectToAnotherServer() throws Exception {
		ClientStorage storage = TestDataRoot.open(Files.createDirectory(temporaryDirectory.resolve("game")), Files.createDirectory(temporaryDirectory.resolve("data")));
		ConnectionJsons.ConnectionInfo creative = new ConnectionJsons.ConnectionInfo(AddressHelpers.parseOrigin("creative.example.com"),
				AddressHelpers.parseEndpoint("downloads.example.com:25564"), ModpackConnectionMode.DIRECT, null, null);
		creative.approveOrigin(AddressHelpers.formatAddress(creative.origin));
		ConnectionStore.saveConnection(storage, "pack111", creative);

		ConnectionJsons.ConnectionInfo survival = new ConnectionJsons.ConnectionInfo(AddressHelpers.parseOrigin("survival.example.com"),
				AddressHelpers.parseEndpoint("downloads2.example.com:25564"), ModpackConnectionMode.DIRECT, null, null);
		ConnectionJsons.ConnectionInfo stored = ConnectionStore.getConnection(storage, "pack111");
		stored.approvedOrigins().forEach(survival::approveOrigin);
		survival.approveOrigin(AddressHelpers.formatAddress(survival.origin));
		ConnectionStore.saveConnection(storage, "pack111", survival);

		ConnectionJsons.ConnectionInfo reloaded = ConnectionStore.getConnection(storage, "pack111");
		assertTrue(reloaded.isApprovedOrigin(AddressHelpers.parseOrigin("creative.example.com")));
		assertTrue(reloaded.isApprovedOrigin(AddressHelpers.parseOrigin("survival.example.com")));
		assertEquals("survival.example.com:25565", AddressHelpers.formatAddress(reloaded.origin));
	}

	@Test
	void recordWithoutApprovalsTreatsEveryOriginAsUnapproved() throws Exception {
		ClientStorage storage = TestDataRoot.open(Files.createDirectory(temporaryDirectory.resolve("game")), Files.createDirectory(temporaryDirectory.resolve("data")));
		Files.createDirectories(storage.connectionFile("pack111").getParent());
		Files.writeString(storage.connectionFile("pack111"), """
				{"connection": {"origin": "creative.example.com", "endpoint": "downloads.example.com:25564", "connectionMode": "DIRECT"}}
				""");

		ConnectionJsons.ConnectionInfo stored = ConnectionStore.getConnection(storage, "pack111");
		assertEquals(List.of(), stored.approvedOrigins());
		assertFalse(stored.isApprovedOrigin(AddressHelpers.parseOrigin("creative.example.com")));
	}
}
