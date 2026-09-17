package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.ManifestFetcher;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.Journal;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The full conditional fetch chain against a real TLS server: the first contact writes the fetched head through to the
 * installed mirror, and the second contact validates against that mirror instead of re-downloading the document.
 */
class ManifestFetcherMirrorChainTest {
	private static final String MODPACK_ID = "abc1234";

	@TempDir
	Path temporaryDirectory;

	private ConditionalFetchTest.ContractServer server;

	@AfterEach
	void tearDown() throws Exception {
		if (server != null) server.close();
	}

	@Test
	void firstFetchInstallsTheHeadMirrorAndTheSecondFetchIsUnchanged() throws Exception {
		server = new ConditionalFetchTest.ContractServer();
		GroupManifest manifest = TestPacks.manifest("mirror chain test", "config/example.txt", "chain-content");
		GenerationJsons.HeadDocumentFields head = TestPacks.head(manifest);
		byte[] headBytes = ConfigTools.GSON.toJson(head).getBytes(StandardCharsets.UTF_8);
		server.store().put("head", headBytes);
		server.store().put("journal", journalBytes(head.contentToken, manifest));

		ClientStorage storage = storage();
		var first = ManifestFetcher.requestServerModpackContentAsync(storage, connectionInfo(), secret(), false, MODPACK_ID).get(15, TimeUnit.SECONDS);
		assertTrue(first.successful(), () -> "first fetch failed: " + first.failure());
		assertTrue(Files.exists(storage.historyHeadFile(MODPACK_ID)), "The fetched head document must land in the installed mirror");
		assertEquals(new String(headBytes, StandardCharsets.UTF_8), Files.readString(storage.historyHeadFile(MODPACK_ID), StandardCharsets.UTF_8));
		// The install the first sync drives: the active generation now points at the fetched head's token.
		storage.writeActiveState(MODPACK_ID, head.contentToken, head.ownershipLedger);

		// The second contact carries the mirror's hash as the validator: the server answers without a body and the
		// content is served from the mirror. The flow's local verification runs regardless — only the transfer is skipped.
		var second = ManifestFetcher.requestServerModpackContentAsync(storage, connectionInfo(), secret(), false, MODPACK_ID).get(15, TimeUnit.SECONDS);
		assertTrue(second.successful(), () -> "second fetch failed: " + second.failure());
		assertEquals(head.contentToken, second.content().contentToken);
		assertEquals(head.policySha1, second.content().policySha1);
		assertEquals(new String(headBytes, StandardCharsets.UTF_8), Files.readString(storage.historyHeadFile(MODPACK_ID), StandardCharsets.UTF_8));
	}

	private byte[] journalBytes(String headToken, GroupManifest manifest) throws IOException {
		Path file = Files.createTempFile(temporaryDirectory, "journal-", ".jsonl");
		Journal journal = Journal.open(file);
		journal.append(new JournalEntry(1, headToken, HashUtils.sha1(ConfigTools.GSON.toJson(manifest.toFields()).getBytes(StandardCharsets.UTF_8)), TestPacks.CREATED, "First",
				JournalEntry.NO_RESTORE, List.of(JournalEntry.Change.added("config/example.txt", headToken, 1))));
		byte[] bytes = Files.readAllBytes(file);
		Files.delete(file);
		return bytes;
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private ConnectionJsons.ConnectionInfo connectionInfo() throws Exception {
		return new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
				new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.DIRECT, server.fingerprint(), null);
	}

	private Secrets.Secret secret() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return new Secrets.Secret(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), 0L);
	}
}
