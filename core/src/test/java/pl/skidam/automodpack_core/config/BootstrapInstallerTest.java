package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.OriginTrustStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.ServerListFile;

class BootstrapInstallerTest {
	private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@TempDir
	Path temporaryDirectory;

	@Test
	void importsInstallFileThenDeletesIt() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("game"));
		Path data = Files.createDirectory(temporaryDirectory.resolve("data"));
		ClientStorage storage = TestDataRoot.open(game, data);
		String secret = Secrets.generateSecret().secret();
		ConnectionJsons.KnownHostsBootstrapFields fields = BootstrapConfig.install(AddressHelpers.parseOrigin("play.example.com"), FINGERPRINT, "abc1234",
				AddressHelpers.parseEndpoint("downloads.example.com:25564"), ModpackConnectionMode.DIRECT, secret, "Pack Server");
		ConfigTools.writeAtomic(storage.bootstrapFile(), fields);

		ClientConfigJsons.ClientConfigFieldsV3 clientConfig = new ClientConfigJsons.ClientConfigFieldsV3();
		clientConfig.selectedModpackId = "oldpack1";
		BootstrapInstaller.Receipt receipt = BootstrapInstaller.importIfPresent(storage, clientConfig).orElseThrow();

		assertFalse(Files.exists(storage.bootstrapFile()));
		assertEquals("abc1234", receipt.clientConfig().selectedModpackId);
		assertTrue(receipt.hasSecret());
		assertEquals("SEED", OriginTrustStore.get(storage, AddressHelpers.parseOrigin("play.example.com")).reason);
		assertEquals("downloads.example.com:25564", AddressHelpers.formatAddress(ConnectionStore.getConnection(storage, "abc1234").endpoint));
		assertEquals(secret, ConnectionStore.getClientSecret(storage, "abc1234", AddressHelpers.parseOrigin("play.example.com")).secret());
		List<ServerListFile.Entry> servers = ServerListFile.read(game.resolve("servers.dat"));
		assertEquals(1, servers.size());
		assertEquals("Pack Server", servers.get(0).name());
		assertEquals("play.example.com:25565", servers.get(0).ip());
	}

	@Test
	void ignoresInstanceRootDropFile() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("root-game"));
		Path data = Files.createDirectory(temporaryDirectory.resolve("root-data"));
		ClientStorage storage = TestDataRoot.open(game, data);
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = "evil.example.com";
		ConfigTools.writeAtomic(game.resolve("automodpack-bootstrap.json"), fields);

		assertTrue(BootstrapInstaller.importIfPresent(storage, new ClientConfigJsons.ClientConfigFieldsV3()).isEmpty());
		assertTrue(Files.exists(game.resolve("automodpack-bootstrap.json")));
		assertNull(OriginTrustStore.get(storage, AddressHelpers.parseOrigin("evil.example.com")));
	}

	@Test
	void originOnlyDoesNotWriteServersDat() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("origin-game"));
		Path data = Files.createDirectory(temporaryDirectory.resolve("origin-data"));
		ClientStorage storage = TestDataRoot.open(game, data);
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = "play.example.com";
		ConfigTools.writeAtomic(storage.bootstrapFile(), fields);

		BootstrapInstaller.Receipt receipt = BootstrapInstaller.importIfPresent(storage, new ClientConfigJsons.ClientConfigFieldsV3()).orElseThrow();
		assertFalse(receipt.bootstrap().hasFingerprint());
		assertNull(OriginTrustStore.get(storage, AddressHelpers.parseOrigin("play.example.com")));
		assertFalse(Files.exists(game.resolve("servers.dat")));
	}

	@Test
	void leavesMissingFileAlone() throws Exception {
		Path game = Files.createDirectory(temporaryDirectory.resolve("missing-game"));
		Path data = Files.createDirectory(temporaryDirectory.resolve("missing-data"));
		ClientStorage storage = TestDataRoot.open(game, data);
		assertTrue(BootstrapInstaller.importIfPresent(storage, new ClientConfigJsons.ClientConfigFieldsV3()).isEmpty());
	}
}
