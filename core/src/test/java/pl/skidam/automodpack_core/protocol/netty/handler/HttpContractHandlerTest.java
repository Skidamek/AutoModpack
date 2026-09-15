package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The handler runs bare on an EmbeddedChannel: the real pipeline adds TLS in front and the shaper behind it. */
class HttpContractHandlerTest {
	@TempDir
	Path tempDir;

	private NettyServer server;
	private final List<EmbeddedChannel> channels = new ArrayList<>();

	@AfterEach
	void tearDown() {
		for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
		if (server != null) server.stop();
	}

	@Test
	void headAndJournalRoutesServeDocumentsWithEtags() throws Exception {
		Fixture fixture = fixture();
		String head = Files.readString(fixture.headPath(), StandardCharsets.UTF_8);
		byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
		EmbeddedChannel channel = channel();

		String response = exchange(channel, request("/head"));
		assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
		assertTrue(response.contains("Content-Length: " + headBytes.length + "\r\n"), response);
		assertTrue(response.contains("Content-Type: application/octet-stream\r\n"), response);
		assertTrue(response.contains("ETag: \"" + HashUtils.getHash(fixture.headPath()) + "\"\r\n"), response);
		assertEquals(head, bodyOf(response));

		String journal = exchange(channel, request("/journal"));
		assertTrue(journal.startsWith("HTTP/1.1 200 OK\r\n"), journal);
		assertEquals(Files.readString(fixture.journalPath(), StandardCharsets.UTF_8), bodyOf(journal));
		assertTrue(channel.isOpen());
	}

	@Test
	void objectRoutesNormalizeCaseAndRejectBadKeys() throws Exception {
		Fixture fixture = fixture();
		EmbeddedChannel channel = channel();

		String response = exchange(channel, request("/objects/" + fixture.objectHash().toUpperCase(Locale.ROOT)));
		assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
		assertTrue(response.contains("ETag: \"" + fixture.objectHash() + "\"\r\n"), response);
		assertEquals(fixture.objectContent(), bodyOf(response));

		assertEquals("HTTP/1.1 404 Not Found", statusOf(exchange(channel, request("/objects/zzzz"))));
		assertEquals("HTTP/1.1 404 Not Found", statusOf(exchange(channel, request("/unknown"))));
		assertEquals("HTTP/1.1 404 Not Found", statusOf(exchange(channel, request("/head/"))));
		assertEquals("HTTP/1.1 404 Not Found", statusOf(exchange(channel, request("/head?v=2"))));
		assertTrue(channel.isOpen());
	}

	@Test
	void nonGetIsRejectedAndPercentEncodingIsABadRequest() throws Exception {
		fixture();
		EmbeddedChannel channel = channel();

		assertTrue(exchange(channel, "POST /head HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 405 Method Not Allowed\r\n"));
		assertTrue(channel.isOpen());
		assertTrue(exchange(channel, request("/head%2Fx")).startsWith("HTTP/1.1 400 Bad Request\r\n"));
		assertFalse(channel.isOpen());
	}

	@Test
	void ifNoneMatchReturnsNotModifiedOnMatchOnly() throws Exception {
		Fixture fixture = fixture();
		String etag = HashUtils.getHash(fixture.headPath());
		EmbeddedChannel channel = channel();

		String quoted = exchange(channel, request("/head", "If-None-Match: \"" + etag + "\""));
		assertTrue(quoted.startsWith("HTTP/1.1 304 Not Modified\r\n"), quoted);
		assertTrue(quoted.contains("ETag: \"" + etag + "\"\r\n"), quoted);
		assertEquals("", bodyOf(quoted));

		String bare = exchange(channel, request("/head", "If-None-Match: " + etag));
		assertTrue(bare.startsWith("HTTP/1.1 304 Not Modified\r\n"), bare);

		String mismatch = exchange(channel, request("/head", "If-None-Match: \"" + "0".repeat(40) + "\""));
		assertTrue(mismatch.startsWith("HTTP/1.1 200 OK\r\n"), mismatch);
		assertEquals(Files.readString(fixture.headPath(), StandardCharsets.UTF_8), bodyOf(mismatch));
	}

	@Test
	void rangesServeSlicesAndDegradeToFullBodies() throws Exception {
		Fixture fixture = fixture();
		byte[] headBytes = Files.readAllBytes(fixture.headPath());
		EmbeddedChannel channel = channel();

		String openEnded = exchange(channel, request("/head", "Range: bytes=7-"));
		assertTrue(openEnded.startsWith("HTTP/1.1 206 Partial Content\r\n"), openEnded);
		assertTrue(openEnded.contains("Content-Range: bytes 7-" + (headBytes.length - 1) + "/" + headBytes.length + "\r\n"), openEnded);
		assertTrue(openEnded.contains("Content-Length: " + (headBytes.length - 7) + "\r\n"), openEnded);
		assertEquals(new String(headBytes, 7, headBytes.length - 7, StandardCharsets.UTF_8), bodyOf(openEnded));

		String bounded = exchange(channel, request("/head", "Range: bytes=2-9"));
		assertTrue(bounded.startsWith("HTTP/1.1 206 Partial Content\r\n"), bounded);
		assertTrue(bounded.contains("Content-Range: bytes 2-9/" + headBytes.length + "\r\n"), bounded);
		assertEquals(new String(headBytes, 2, 8, StandardCharsets.UTF_8), bodyOf(bounded));

		String clamped = exchange(channel, request("/head", "Range: bytes=2-999999"));
		assertTrue(clamped.startsWith("HTTP/1.1 206 Partial Content\r\n"), clamped);
		assertTrue(clamped.contains("Content-Range: bytes 2-" + (headBytes.length - 1) + "/" + headBytes.length + "\r\n"), clamped);

		String unsatisfiable = exchange(channel, request("/head", "Range: bytes=" + headBytes.length + "-"));
		assertTrue(unsatisfiable.startsWith("HTTP/1.1 416 Range Not Satisfiable\r\n"), unsatisfiable);
		assertTrue(unsatisfiable.contains("Content-Range: bytes */" + headBytes.length + "\r\n"), unsatisfiable);
		assertEquals("", bodyOf(unsatisfiable));

		for (String malformed : new String[]{"bytes=abc-", "bytes=-5", "bytes=0-1,5-9", "bytes=9-2", "chunks=0-1"}) {
			String full = exchange(channel, request("/head", "Range: " + malformed));
			assertTrue(full.startsWith("HTTP/1.1 200 OK\r\n"), malformed + " -> " + full);
			assertEquals(new String(headBytes, StandardCharsets.UTF_8), bodyOf(full));
		}
		assertTrue(channel.isOpen());
	}

	@Test
	void keepAliveServesSequentialRequestsOnOneConnection() throws Exception {
		Fixture fixture = fixture();
		EmbeddedChannel channel = channel();

		String first = exchange(channel, request("/head", "Connection: keep-alive"));
		assertTrue(first.startsWith("HTTP/1.1 200 OK\r\n"), first);
		String second = exchange(channel, request("/objects/" + fixture.objectHash()));
		assertTrue(second.startsWith("HTTP/1.1 200 OK\r\n"), second);
		assertEquals(fixture.objectContent(), bodyOf(second));
		assertTrue(channel.isOpen());

		assertTrue(exchange(channel, request("/head", "Connection: close")).startsWith("HTTP/1.1 200 OK\r\n"));
		assertFalse(channel.isOpen());
	}

	@Test
	void oversizedHeaderBlocksAndGarbageCloseTheConnection() throws Exception {
		fixture();
		EmbeddedChannel channel = channel();
		String padded = "GET /head HTTP/1.1\r\nX-Pad: " + "a".repeat(9000) + "\r\n";
		channel.writeInbound(Unpooled.wrappedBuffer(padded.getBytes(StandardCharsets.UTF_8)));
		channel.runPendingTasks();
		assertFalse(channel.isOpen());

		EmbeddedChannel garbage = channel();
		garbage.writeInbound(Unpooled.wrappedBuffer("NOT-HTTP AT ALL\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
		garbage.runPendingTasks();
		assertFalse(garbage.isOpen());
	}

	private EmbeddedChannel channel() {
		EmbeddedChannel channel = new EmbeddedChannel(new HttpContractHandler(server, Runnable::run));
		channels.add(channel);
		return channel;
	}

	private static String request(String target, String... headers) {
		StringBuilder request = new StringBuilder("GET ").append(target).append(" HTTP/1.1\r\n");
		for (String header : headers) request.append(header).append("\r\n");
		return request.append("\r\n").toString();
	}

	private static String exchange(EmbeddedChannel channel, String request) {
		channel.writeInbound(Unpooled.wrappedBuffer(request.getBytes(StandardCharsets.UTF_8)));
		channel.runPendingTasks();
		return drain(channel);
	}

	private static String drain(EmbeddedChannel channel) {
		StringBuilder response = new StringBuilder();
		Object message;
		while ((message = channel.readOutbound()) != null) {
			try {
				response.append(((ByteBuf) message).toString(StandardCharsets.UTF_8));
			} finally {
				ReferenceCountUtil.release(message);
			}
		}
		return response.toString();
	}

	private static String statusOf(String response) {
		return response.substring(0, response.indexOf("\r\n"));
	}

	private static String bodyOf(String response) {
		int head = response.indexOf("\r\n\r\n");
		return head < 0 ? "" : response.substring(head + 4);
	}

	private Fixture fixture() throws Exception {
		GenerationStore store = new GenerationStore(tempDir.resolve("host-generations"), tempDir.resolve("objects"));
		byte[] bytes = "object-payload".getBytes(StandardCharsets.UTF_8);
		Path staging = tempDir.resolve("host-generations").resolve("staging");
		Files.createDirectories(staging);
		Path staged = Files.createTempFile(staging, "candidate-", ".staged");
		Files.write(staged, bytes);
		String hash = HashUtils.getHash(staged);
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = "test";
		group.files = Map.of("config/example.txt", new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(bytes.length), "config", false, hash, null));
		fields.groups = Map.of("main", group);
		ModpackCandidate candidate = new ModpackCandidate(GroupManifestValidator.validate(fields), new TreeMap<>(Map.of(hash, new StagedObject(hash, bytes.length, staged))), new TreeMap<>(), List.of());
		GenerationStore.Publication publication = store.publish(candidate, "");
		server = new NettyServer();
		server.replacePaths(publication.hostingPaths());
		return new Fixture(hash, new String(bytes, StandardCharsets.UTF_8), tempDir.resolve("host-generations").resolve("current-projection.json"),
				tempDir.resolve("host-generations").resolve("journal.jsonl"));
	}

	private record Fixture(String objectHash, String objectContent, Path headPath, Path journalPath) {}
}
