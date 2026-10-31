package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;

class InstalledPacksTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void listsAnInstalledPackWithItsCountsFlagsAndFallbackName() throws Exception {
		ClientStorage storage = storage();
		GroupManifest manifest = installed(storage);

		InstalledPacks.Catalogue catalogue = InstalledPacks.read(storage, InstalledPacks.ServerNames.NONE);
		assertNull(catalogue.failure());
		InstalledPacks.Pack pack = catalogue.byId(manifest.modpackId());
		assertNotNull(pack);
		assertEquals("abc1234", pack.name(), "no manifest name and no connection falls back to the id");
		assertTrue(pack.active());
		assertFalse(pack.detached());
		assertFalse(pack.connectionAvailable());
		assertEquals(1, pack.fileCount());
		assertEquals(5, pack.fileBytes());
	}

	@Test
	void aSavedOriginSuppliesTheNameWhenThePackDeclaresNone() throws Exception {
		ClientStorage storage = storage();
		GroupManifest manifest = installed(storage);
		ConnectionJsons.ConnectionInfo connection = new ConnectionJsons.ConnectionInfo(AddressHelpers.parseOrigin("play.example.com:25565"),
				AddressHelpers.parseEndpoint("downloads.example.com:25564"), ModpackConnectionMode.HTTP, null);
		connection.approveOrigin(AddressHelpers.formatAddress(connection.origin));
		ConnectionStore.saveConnection(storage, manifest.modpackId(), connection);

		InstalledPacks.Pack named = InstalledPacks.read(storage, manifest.modpackId(), origin -> "Vanilla Server");
		assertEquals("Vanilla Server", named.name());
		assertTrue(named.connectionAvailable());
		assertEquals("play.example.com", named.connectionOrigin(), "the default port is not shown");

		InstalledPacks.Pack unnamed = InstalledPacks.read(storage, manifest.modpackId(), InstalledPacks.ServerNames.NONE);
		assertEquals("play.example.com", unnamed.name());
	}

	@Test
	void anUnreadableActiveStateIsReportedWithoutHidingThePacks() throws Exception {
		ClientStorage storage = storage();
		GroupManifest manifest = installed(storage);
		Files.writeString(storage.stateFile(), "{not json", StandardCharsets.UTF_8);

		InstalledPacks.Catalogue catalogue = InstalledPacks.read(storage, InstalledPacks.ServerNames.NONE);
		assertNotNull(catalogue.failure(), "the read failure is reported with the list");
		InstalledPacks.Pack pack = catalogue.byId(manifest.modpackId());
		assertNotNull(pack, "the pack is still listed, just not active");
		assertFalse(pack.active());
	}

	/** One installed, consented pack with a single five-byte file, active on this client. */
	private static GroupManifest installed(ClientStorage storage) throws Exception {
		GroupManifest manifest = TestPacks.manifest("pack", "config/a.toml", "hello");
		PackDocument document = TestPacks.document(manifest);
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(document);
		new ClientSelectionStore(storage.selectionFile()).put(manifest.modpackId(), new SelectionIntent(Set.of("main")));
		return manifest;
	}

	private ClientStorage storage() throws Exception {
		return TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
	}
}
