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

	@Test
	void aVouchedRecheckSurvivesACloseFramedHost() throws Exception {
		server = new ConditionalFetchTest.ContractServer();
		GroupManifest manifest = TestPacks.manifest("close framed recheck", "config/example.txt", "chain-content");
		GenerationJsons.HeadDocumentFields head = TestPacks.head(manifest);
		byte[] headBytes = ConfigTools.GSON.toJson(head).getBytes(StandardCharsets.UTF_8);
		server.store().put("head", headBytes);
		server.store().put("journal", journalBytes(head.contentToken, manifest));

		ClientStorage storage = storage();
		var first = ManifestFetcher.requestServerModpackContentAsync(storage, connectionInfo(), secret(), false, MODPACK_ID).get(15, TimeUnit.SECONDS);
		assertTrue(first.successful(), () -> "first fetch failed: " + first.failure());
		assertTrue(Files.exists(storage.historyHeadFile(MODPACK_ID)), "the first fetch must install the vouching head mirror");
		// The install the first sync drives: the active generation now points at the fetched head's token, so the
		// re-check's mirror vouches and the journal fetch pipelines behind the head.
		storage.writeActiveState(MODPACK_ID, head.contentToken, head.ownershipLedger);

		// The re-check vouches and pipelines the journal behind the head; the host close-frames every document, so the
		// head's body ends at EOF and spends the lane - the pipelined journal response never parses. One fresh-lane
		// retry must recover the re-check instead of failing the whole manifest fetch forever.
		server.cooperate.set(false);
		server.closeFramedDocuments.set(true);
		var second = ManifestFetcher.requestServerModpackContentAsync(storage, connectionInfo(), secret(), false, MODPACK_ID).get(15, TimeUnit.SECONDS);
		assertTrue(second.successful(), () -> "vouched re-check failed: " + second.failure());
		assertEquals(head.contentToken, second.content().contentToken);
		assertEquals(new String(headBytes, StandardCharsets.UTF_8), Files.readString(storage.historyHeadFile(MODPACK_ID), StandardCharsets.UTF_8));
	}


	/**
	 * The S3 hosting shape end to end: the host mints MD5 etags that are never our sha1, so only the client's cached
	 * host validator earns a 304. First contact is unconditional; the second sync replays the cached etags and both
	 * documents answer 304; a changed generation answers 200, refreshes the cache, and the sync after that is 304s
	 * again. Two requests per unchanged sync, zero bodies - the whole point of bucket hosting.
	 */
	@Test
	void aForeignEtagHostCachesValidatorsAndUnchangedSyncsStayCheap() throws Exception {
		server = new ConditionalFetchTest.ContractServer();
		server.foreignEtags.set(true);
		GroupManifest manifest = TestPacks.manifest("foreign etag test", "config/example.txt", "chain-content");
		GenerationJsons.HeadDocumentFields head = TestPacks.head(manifest);
		byte[] headBytes = ConfigTools.GSON.toJson(head).getBytes(StandardCharsets.UTF_8);
		server.store().put("head", headBytes);
		server.store().put("journal", journalBytes(head.contentToken, manifest));

		ClientStorage storage = storage();
		var first = ManifestFetcher.requestServerModpackContentAsync(storage, connectionInfo(), secret(), false, MODPACK_ID).get(15, TimeUnit.SECONDS);
		assertTrue(first.successful(), () -> "first fetch failed: " + first.failure());
		storage.writeActiveState(MODPACK_ID, head.contentToken, head.ownershipLedger);

		int afterFirst = server.requests.size();
		var second = ManifestFetcher.requestServerModpackContentAsync(storage, connectionInfo(), secret(), false, MODPACK_ID).get(15, TimeUnit.SECONDS);
		assertTrue(second.successful(), () -> "second fetch failed: " + second.failure());
		assertEquals(new String(headBytes, StandardCharsets.UTF_8), Files.readString(storage.historyHeadFile(MODPACK_ID)));
		// The recheck pipelines head + journal: exactly two more requests, both carrying the cached foreign etag
		// behind the mirror sha1, both answered 304.
		assertEquals(afterFirst + 2, server.requests.size(), "requests=" + server.requests + " ifnm=" + server.ifNoneMatchLog);
		assertTrue(server.ifNoneMatchLog.stream().anyMatch(value -> value.contains(server.foreignEtag(headBytes))),
				"the replayed foreign etag must ride the dual validator");
		assertTrue(server.ifNoneMatchLog.stream().allMatch(value -> value.startsWith("\"") && value.contains("\", \"")),
				"every conditional request carries the sha1 first and the host etag behind it");

		// A changed generation: both documents answer 200, the new bytes land in the mirrors, and the freshly served
		// etags are cached - the sync after the change is 304s again.
		GroupManifest updated = TestPacks.manifest("foreign etag test", "config/example.txt", "changed-content");
		GenerationJsons.HeadDocumentFields newHead = TestPacks.head(updated);
		byte[] newHeadBytes = ConfigTools.GSON.toJson(newHead).getBytes(StandardCharsets.UTF_8);
		byte[] newJournalBytes = journalBytes(newHead.contentToken, updated);
		server.store().put("head", newHeadBytes);
		server.store().put("journal", newJournalBytes);
		int afterSecond = server.requests.size();
		var third = ManifestFetcher.requestServerModpackContentAsync(storage, connectionInfo(), secret(), false, MODPACK_ID).get(15, TimeUnit.SECONDS);
		assertTrue(third.successful(), () -> "third fetch failed: " + third.failure());
		assertEquals(new String(newHeadBytes, StandardCharsets.UTF_8), Files.readString(storage.historyHeadFile(MODPACK_ID)));
		assertEquals(afterSecond + 2, server.requests.size(), "a changed generation must be served, not 304'd");
		// The install the third sync drives: the active generation moves to the new token, so the next recheck vouches.
		storage.writeActiveState(MODPACK_ID, newHead.contentToken, newHead.ownershipLedger);

		int afterThird = server.requests.size();
		var fourth = ManifestFetcher.requestServerModpackContentAsync(storage, connectionInfo(), secret(), false, MODPACK_ID).get(15, TimeUnit.SECONDS);
		assertTrue(fourth.successful(), () -> "fourth fetch failed: " + fourth.failure());
		assertEquals(afterThird + 2, server.requests.size());
		// The pipelined pair logs head first, journal second; both must carry the NEW generation's cached etags.
		var lastTwo = server.ifNoneMatchLog.subList(server.ifNoneMatchLog.size() - 2, server.ifNoneMatchLog.size());
		assertTrue(lastTwo.get(0).contains(server.foreignEtag(newHeadBytes)), "head validator must be the new generation's etag");
		assertTrue(lastTwo.get(1).contains(server.foreignEtag(newJournalBytes)), "journal validator must be the new generation's etag");
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
				new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, server.fingerprint(), null);
	}

	private Secrets.Secret secret() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return new Secrets.Secret(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), 0L);
	}
}
