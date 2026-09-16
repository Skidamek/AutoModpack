package pl.skidam.automodpack_core.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.HashUtils;

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

	@Test
	void stalePacksAreTheInstalledOnesThisOriginNoLongerServes() throws Exception {
		ClientStorage storage = TestDataRoot.open(Files.createDirectory(temporaryDirectory.resolve("game")), Files.createDirectory(temporaryDirectory.resolve("data")));
		ConnectionJsons.ConnectionInfo shared = new ConnectionJsons.ConnectionInfo(AddressHelpers.parseOrigin("shared.example.com"),
				AddressHelpers.parseEndpoint("downloads.example.com:25564"), ModpackConnectionMode.DIRECT, null, null);
		ConnectionJsons.ConnectionInfo elsewhere = new ConnectionJsons.ConnectionInfo(AddressHelpers.parseOrigin("elsewhere.example.com"),
				AddressHelpers.parseEndpoint("other.example.com:25564"), ModpackConnectionMode.DIRECT, null, null);
		ConnectionStore.saveConnection(storage, "abc1234", shared);
		ConnectionStore.saveConnection(storage, "xyz9876", shared);
		ConnectionStore.saveConnection(storage, "pqr5432", elsewhere);
		TestPacks.stageGeneration(storage, TestPacks.document(manifestFor("abc1234", "active", "config/a.txt", "a")));
		TestPacks.stageGeneration(storage, TestPacks.document(manifestFor("xyz9876", "stale", "config/b.txt", "b")));
		TestPacks.stageGeneration(storage, TestPacks.document(manifestFor("pqr5432", "other origin", "config/c.txt", "c")));
		ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
		selections.compareAndSet("xyz9876", null, GroupSelectionResolver.defaultIntent(manifestFor("xyz9876", "stale", "config/b.txt", "b")));
		selections.compareAndSet("pqr5432", null, GroupSelectionResolver.defaultIntent(manifestFor("pqr5432", "other origin", "config/c.txt", "c")));

		assertEquals(List.of("xyz9876"), ConnectionStore.staleSameOriginPackIds(storage, "abc1234"));
		// A mirror fetch without consent is cache, and the active pack is never its own leftover.
		selections.remove("xyz9876", selections.get("xyz9876").orElseThrow());
		assertEquals(List.of(), ConnectionStore.staleSameOriginPackIds(storage, "abc1234"));
	}

	private static GroupManifest manifestFor(String modpackId, String description, String path, String content) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		String sha1 = HashUtils.sha1(content.getBytes(StandardCharsets.UTF_8));
		group.files = new TreeMap<>(Map.of(path, new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length()), "config", false, sha1, null)));
		fields.categories = Map.of("General", Map.of("main", group));
		return GroupManifestValidator.validate(fields);
	}
}
