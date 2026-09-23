package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.airlift.compress.zstd.ZstdInputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.loader.GameCallService;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The handler runs bare on an EmbeddedChannel: the real pipeline adds TLS in front and the shaper behind it. */
class HttpContractHandlerTest {
	@TempDir
	Path tempDir;

	private ServerConfigJsons.ServerConfigFieldsV3 previousConfig;
	private NettyServer server;
	private final List<EmbeddedChannel> channels = new ArrayList<>();

	@BeforeEach
	void setUp() {
		previousConfig = Constants.serverConfig;
		Constants.serverConfig = new ServerConfigJsons.ServerConfigFieldsV3();
		Constants.serverConfig.validateSecrets = false;
	}

	@AfterEach
	void tearDown() {
		for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
		if (server != null) server.stop();
		Constants.serverConfig = previousConfig;
	}

	@Test
	void headAndJournalRoutesServeDocuments() throws Exception {
		Fixture fixture = fixture();
		String head = Files.readString(fixture.headPath(), StandardCharsets.UTF_8);
		byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
		EmbeddedChannel channel = channel();

		String response = exchange(channel, request("/head"));
		assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
		assertTrue(response.contains("Content-Length: " + headBytes.length + "\r\n"), response);
		assertTrue(response.contains("Content-Type: application/octet-stream\r\n"), response);
		// Document bodies hash on the client side anyway, so plain GETs skip the server-side hash and carry no ETag.
		assertFalse(response.contains("ETag:"), response);
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
		assertEquals("HTTP/1.1 404 Not Found", statusOf(exchange(channel, request("/waiting-music.ogg")))); // the track is an object, never a route
		assertTrue(channel.isOpen());
	}

	@Test
	void nonGetIsRejectedAndPercentEncodingIsABadRequest() throws Exception {
		fixture();
		EmbeddedChannel channel = channel();

		assertTrue(exchange(channel, "POST /head HTTP/1.1\r\nHost: contract.test\r\n\r\n").startsWith("HTTP/1.1 405 Method Not Allowed\r\n"));
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

		// RFC 7232 weak comparison: the W/ prefix and validator lists never change the opaque value, and * matches any.
		String weak = exchange(channel, request("/head", "If-None-Match: W/\"" + etag + "\""));
		assertTrue(weak.startsWith("HTTP/1.1 304 Not Modified\r\n"), weak);
		String listed = exchange(channel, request("/head", "If-None-Match: \"" + "0".repeat(40) + "\", \"" + etag + "\""));
		assertTrue(listed.startsWith("HTTP/1.1 304 Not Modified\r\n"), listed);
		String star = exchange(channel, request("/head", "If-None-Match: *"));
		assertTrue(star.startsWith("HTTP/1.1 304 Not Modified\r\n"), star);

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

	private static byte[] gunzip(byte[] gzipped) throws IOException {
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
			return gzip.readAllBytes();
		}
	}

	/** Concatenates a chunked body - hex size lines, frames, zero terminator - out of the raw response bytes. */
	private static byte[] deframe(byte[] response) {
		int body = 0;
		for (int index = 0; index + 4 <= response.length; index++) {
			if (response[index] == '\r' && response[index + 1] == '\n' && response[index + 2] == '\r' && response[index + 3] == '\n') {
				body = index + 4;
				break;
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int offset = body;
		while (true) {
			int lineEnd = offset;
			while (response[lineEnd] != '\r') lineEnd++;
			int size = Integer.parseInt(new String(response, offset, lineEnd - offset, StandardCharsets.US_ASCII), 16);
			offset = lineEnd + 2;
			if (size == 0) return out.toByteArray();
			out.write(response, offset, size);
			offset += size + 2;
		}
	}

	@Test
	void documentsCompressForZstdClientsAndDecodeToIdentity() throws Exception {
		Fixture fixture = fixture();
		byte[] headBytes = Files.readAllBytes(fixture.headPath());
		EmbeddedChannel channel = channel();

		byte[] response = exchangeBytes(channel, request("/head", "Accept-Encoding: zstd"));
		String head = headOf(response);
		byte[] body = deframe(response);
		assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"), head);
		assertTrue(head.contains("Content-Encoding: zstd\r\n"), head);
		assertTrue(head.contains("Vary: Accept-Encoding\r\n"), head);
		// The compressed length is unknowable before the body exists, so the stream is chunked and carries no length.
		assertTrue(head.contains("Transfer-Encoding: chunked\r\n"), head);
		assertFalse(head.contains("Content-Length"), head);
		assertArrayEquals(headBytes, zstdDecode(body));

		// A header listing several encodings with q-values still token-matches zstd.
		byte[] listed = exchangeBytes(channel, request("/head", "Accept-Encoding: gzip;q=1.0, identity;q=0.5, zstd"));
		assertTrue(headOf(listed).contains("Content-Encoding: zstd\r\n"), headOf(listed));

		// A gzip-only client gets gzip; a client offering both gets zstd, our preferred codec.
		byte[] gzipped = exchangeBytes(channel, request("/head", "Accept-Encoding: gzip"));
		assertTrue(headOf(gzipped).contains("Content-Encoding: gzip\r\n"), headOf(gzipped));
		assertArrayEquals(headBytes, gunzip(deframe(gzipped)));
		byte[] both = exchangeBytes(channel, request("/head", "Accept-Encoding: gzip, zstd"));
		assertTrue(headOf(both).contains("Content-Encoding: zstd\r\n"), headOf(both));

		// Plain negotiation on every body: objects compress for offering clients, ranges included - the slice is
		// selected in file bytes, encoded on the wire, and Content-Range keeps describing file offsets.
		byte[] object = exchangeBytes(channel, request("/objects/" + fixture.objectHash(), "Accept-Encoding: zstd"));
		assertTrue(headOf(object).contains("Content-Encoding: zstd\r\n"), headOf(object));
		assertArrayEquals(fixture.objectContent().getBytes(StandardCharsets.UTF_8), zstdDecode(deframe(object)));
		byte[] ranged = exchangeBytes(channel, request("/objects/" + fixture.objectHash(), "Accept-Encoding: zstd", "Range: bytes=0-4"));
		assertTrue(headOf(ranged).startsWith("HTTP/1.1 206 Partial Content\r\n"), headOf(ranged));
		assertTrue(headOf(ranged).contains("Content-Encoding: zstd\r\n"), headOf(ranged));
		assertTrue(headOf(ranged).contains("Transfer-Encoding: chunked\r\n"), headOf(ranged));
		assertTrue(headOf(ranged).contains("Content-Range: bytes 0-4/" + fixture.objectContent().length() + "\r\n"), headOf(ranged));
		byte[] expectedRange = new byte[5];
		System.arraycopy(fixture.objectContent().getBytes(StandardCharsets.UTF_8), 0, expectedRange, 0, 5);
		assertArrayEquals(expectedRange, zstdDecode(deframe(ranged)));
		// A client that offers nothing gets identity with an ordinary length.
		byte[] plain = exchangeBytes(channel, request("/objects/" + fixture.objectHash()));
		assertFalse(headOf(plain).contains("Content-Encoding"), headOf(plain));
		assertArrayEquals(fixture.objectContent().getBytes(StandardCharsets.UTF_8), bodyOf(plain));
		assertTrue(channel.isOpen());
	}

	@Test
	void aNotModifiedAnswerStaysBodylessUnderAnyEncoding() throws Exception {
		Fixture fixture = fixture();
		String etag = HashUtils.getHash(fixture.headPath());
		EmbeddedChannel channel = channel();

		String response = exchange(channel, request("/head", "If-None-Match: \"" + etag + "\"", "Accept-Encoding: zstd"));
		assertTrue(response.startsWith("HTTP/1.1 304 Not Modified\r\n"), response);
		assertFalse(response.contains("Content-Encoding"), response);
		assertEquals("", bodyOf(response));
	}

	@Test
	void documentsWithoutTheEncodingHeaderStayIdentity() throws Exception {
		Fixture fixture = fixture();
		EmbeddedChannel channel = channel();

		String response = exchange(channel, request("/head"));
		assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
		assertFalse(response.contains("Content-Encoding"), response);
		assertFalse(response.contains("Vary:"), response);
	}

	private static byte[] zstdDecode(byte[] compressed) throws Exception {
		try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
			return zstd.readAllBytes();
		}
	}

	private static byte[] exchangeBytes(EmbeddedChannel channel, String request) {
		channel.writeInbound(Unpooled.wrappedBuffer(request.getBytes(StandardCharsets.UTF_8)));
		settle(channel);
		return drained(channel);
	}

	private static byte[] drained(EmbeddedChannel channel) {
		ByteArrayOutputStream response = new ByteArrayOutputStream();
		Object message;
		while ((message = channel.readOutbound()) != null) {
			try {
				ByteBuf buffer = (ByteBuf) message;
				byte[] bytes = new byte[buffer.readableBytes()];
				buffer.readBytes(bytes);
				response.writeBytes(bytes);
			} finally {
				ReferenceCountUtil.release(message);
			}
		}
		return response.toByteArray();
	}

	private static String headOf(byte[] response) {
		for (int i = 0; i < response.length - 3; i++) {
			if (response[i] == '\r' && response[i + 1] == '\n' && response[i + 2] == '\r' && response[i + 3] == '\n') {
				return new String(response, 0, i + 4, StandardCharsets.UTF_8);
			}
		}
		return new String(response, StandardCharsets.UTF_8);
	}

	private static byte[] bodyOf(byte[] response) {
		for (int i = 0; i < response.length - 3; i++) {
			if (response[i] == '\r' && response[i + 1] == '\n' && response[i + 2] == '\r' && response[i + 3] == '\n') {
				return Arrays.copyOfRange(response, i + 4, response.length);
			}
		}
		return new byte[0];
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

		String closing = exchange(channel, request("/head", "Connection: close"));
		assertTrue(closing.startsWith("HTTP/1.1 200 OK\r\n"), closing);
		// The close is announced in the head, so a pipelining client knows its queued request is lost.
		assertTrue(closing.contains("Connection: close\r\n"), closing);
		assertFalse(channel.isOpen());
	}

	@Test
	void malformedHeaderLinesAndMissingHostAreBadRequests() throws Exception {
		fixture();

		EmbeddedChannel folded = channel();
		assertTrue(exchange(folded, "GET /head HTTP/1.1\r\nHost: contract.test\r\n X-Folded: continued\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request\r\n"));
		assertFalse(folded.isOpen());

		EmbeddedChannel spacedName = channel();
		assertTrue(exchange(spacedName, "GET /head HTTP/1.1\r\nHost : contract.test\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request\r\n"));
		assertFalse(spacedName.isOpen());

		EmbeddedChannel noHost = channel();
		assertTrue(exchange(noHost, "GET /head HTTP/1.1\r\nAccept: nothing\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request\r\n"));
		assertFalse(noHost.isOpen());

		// HTTP/1.0 predates the Host requirement.
		EmbeddedChannel http10NoHost = channel();
		assertTrue(exchange(http10NoHost, "GET /head HTTP/1.0\r\n\r\n").startsWith("HTTP/1.1 200 OK\r\n"));
		assertFalse(http10NoHost.isOpen());
	}

	@Test
	void http10GetsIdentityContentLengthWithoutTransferEncoding() throws Exception {
		Fixture fixture = fixture();
		EmbeddedChannel channel = channel();

		String response = exchange(channel, "GET /head HTTP/1.0\r\nHost: contract.test\r\nAccept-Encoding: zstd\r\n\r\n");
		assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
		// HTTP/1.0 has no Transfer-Encoding, so offering an encoding changes nothing: the body goes out identity Content-Length.
		assertTrue(response.contains("Content-Length: " + Files.size(fixture.headPath()) + "\r\n"), response);
		assertFalse(response.contains("Transfer-Encoding"), response);
		assertFalse(response.contains("Content-Encoding"), response);
		assertEquals(Files.readString(fixture.headPath(), StandardCharsets.UTF_8), bodyOf(response));
		assertFalse(channel.isOpen());
	}

	@Test
	void aZeroQualityEncodingIsNotNegotiated() throws Exception {
		Fixture fixture = fixture();
		EmbeddedChannel channel = channel();

		String response = exchange(channel, request("/objects/" + fixture.objectHash(), "Accept-Encoding: gzip;q=0"));
		assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
		assertFalse(response.contains("Content-Encoding"), response);
		assertTrue(response.contains("Content-Length: " + fixture.objectContent().length() + "\r\n"), response);
		assertEquals(fixture.objectContent(), bodyOf(response));
		assertTrue(channel.isOpen());
	}

	@Test
	void errorAndNotModifiedHeadsCarryTheRequiredFields() throws Exception {
		Fixture fixture = fixture();
		String etag = HashUtils.getHash(fixture.headPath());
		EmbeddedChannel channel = channel();

		String notAllowed = exchange(channel, "POST /head HTTP/1.1\r\nHost: contract.test\r\n\r\n");
		assertTrue(notAllowed.startsWith("HTTP/1.1 405 Method Not Allowed\r\n"), notAllowed);
		assertTrue(notAllowed.contains("Allow: GET\r\n"), notAllowed);
		assertTrue(notAllowed.contains("Date: "), notAllowed);

		String notModified = exchange(channel, request("/head", "If-None-Match: \"" + etag + "\""));
		assertTrue(notModified.startsWith("HTTP/1.1 304 Not Modified\r\n"), notModified);
		// The 304 head states the length a 200 would have sent, while the books stay at zero served bytes.
		assertTrue(notModified.contains("Content-Length: " + Files.size(fixture.headPath()) + "\r\n"), notModified);
		assertEquals("", bodyOf(notModified));
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

	@Test
	void theAuthorizationHeaderIsIgnoredWhenSecretValidationIsOff() throws Exception {
		fixture();
		EmbeddedChannel channel = channel();

		assertTrue(exchange(channel, request("/head", "Authorization: Bearer not-a-secret")).startsWith("HTTP/1.1 200 OK\r\n"));
		assertTrue(channel.isOpen());
	}

	@Test
	void missingMalformedAndUnknownBearerSecretsAreUnauthorized() throws Exception {
		fixture();
		Constants.serverConfig.validateSecrets = true;

		EmbeddedChannel missing = channel();
		assertTrue(exchange(missing, request("/head")).startsWith("HTTP/1.1 401 Unauthorized\r\n"));
		assertFalse(missing.isOpen());

		EmbeddedChannel malformed = channel();
		assertTrue(exchange(malformed, request("/head", "Authorization: Basic dXNlcjpwYXNz")).startsWith("HTTP/1.1 401 Unauthorized\r\n"));
		assertFalse(malformed.isOpen());

		EmbeddedChannel emptyBearer = channel();
		assertTrue(exchange(emptyBearer, request("/head", "Authorization: Bearer ")).startsWith("HTTP/1.1 401 Unauthorized\r\n"));
		assertFalse(emptyBearer.isOpen());

		EmbeddedChannel unknown = channel();
		Secrets.Secret secret = Secrets.generateSecret();
		String unknownResponse = exchange(unknown, request("/head", "Authorization: Bearer " + secret.secret()));
		assertTrue(unknownResponse.startsWith("HTTP/1.1 401 Unauthorized\r\n"), unknownResponse);
		assertEquals("", bodyOf(unknownResponse));
		assertFalse(unknown.isOpen());
	}

	@Test
	void aValidBearerSecretIsAccepted() throws Exception {
		fixture();
		Constants.serverConfig.validateSecrets = true;
		GameCallService previousGameCall = Constants.GAME_CALL;
		Constants.GAME_CALL = (address, id, playerName) -> true;
		Secrets.Secret secret = Secrets.generateSecret();
		SecretsStore.saveHostSecret("test-player", secret, "Test Player");

		try {
			EmbeddedChannel channel = channel();
			assertTrue(exchange(channel, request("/head", "Authorization: Bearer " + secret.secret())).startsWith("HTTP/1.1 200 OK\r\n"));
			assertTrue(channel.isOpen());
		} finally {
			Constants.GAME_CALL = previousGameCall;
		}
	}

	private EmbeddedChannel channel() {
		EmbeddedChannel channel = new EmbeddedChannel(new HttpContractHandler(server, Runnable::run));
		channels.add(channel);
		return channel;
	}

	private static String request(String target, String... headers) {
		StringBuilder request = new StringBuilder("GET ").append(target).append(" HTTP/1.1\r\nHost: contract.test\r\n");
		for (String header : headers) request.append(header).append("\r\n");
		return request.append("\r\n").toString();
	}

	/**
	 * The one-stream-per-connection pin: a request pipelined while an identity body streams is held (accumulated) and
	 * served strictly after the first response completes - never parsed mid-body into the first body's byte stream.
	 */
	@Test
	void aPipelinedRequestWaitsForTheStreamingIdentityBodyToFinish() throws Exception {
		byte[] body = new byte[NetUtils.STREAM_WRITE_BYTES * 3];
		Path object = tempDir.resolve("pipeline.bin");
		Files.write(object, body);
		String hash = HashUtils.sha1(body);
		NettyServer pipelineServer = new NettyServer() {
			@Override
			public Optional<Path> getPath(String requestKey) {
				return requestKey.equals(hash) ? Optional.of(object) : Optional.empty();
			}
		};
		HoldableChannel channel = holdableChannel(pipelineServer, "/objects/" + hash);

		// A request pipelined mid-body is held, not answered into the first response's byte stream.
		channel.writeInbound(Unpooled.wrappedBuffer(request("/journal").getBytes(StandardCharsets.UTF_8)));
		channel.runPendingTasks();
		assertEquals(1, channel.outboundMessages().size(), "a pipelined request must be held while a body streams");

		// Resume the drain; the held request is served strictly after the first response completes.
		channel.resume();
		settle(channel);

		byte[] wire = drained(channel);
		String firstHead = headOf(wire);
		assertTrue(firstHead.startsWith("HTTP/1.1 200 OK\r\n"), firstHead);
		assertArrayEquals(body, Arrays.copyOfRange(wire, firstHead.length(), firstHead.length() + body.length));
		int secondHeadStart = firstHead.length() + body.length;
		String secondHead = new String(wire, secondHeadStart, Math.min(40, wire.length - secondHeadStart), StandardCharsets.US_ASCII);
		assertTrue(secondHead.startsWith("HTTP/1.1 404 Not Found\r\n"), secondHead);
		assertEquals(wire.length, secondHeadStart + headOf(Arrays.copyOfRange(wire, secondHeadStart, wire.length)).length());
	}

	/**
	 * The held-request cap is deep enough for the client's whole count-tripwire pipeline: 2048 pipelined heads behind a
	 * streaming body are all held with the connection open, then answered in order once the body finishes.
	 */
	@Test
	void twoThousandFortyEightPipelinedHeadsAreHeldWhileABodyStreams() throws Exception {
		byte[] body = new byte[NetUtils.STREAM_WRITE_BYTES * 3];
		Path object = tempDir.resolve("held.bin");
		Files.write(object, body);
		String hash = HashUtils.sha1(body);
		NettyServer heldServer = new NettyServer() {
			@Override
			public Optional<Path> getPath(String requestKey) {
				return requestKey.equals(hash) ? Optional.of(object) : Optional.empty();
			}
		};
		HoldableChannel channel = holdableChannel(heldServer, "/objects/" + hash);

		byte[] heads = request("/journal").repeat(NetUtils.PIPELINE_MAX_REQUESTS).getBytes(StandardCharsets.UTF_8);
		channel.writeInbound(Unpooled.wrappedBuffer(heads));
		channel.runPendingTasks();
		assertTrue(channel.isOpen(), "a deep pipeline of held heads must not close the connection");
		assertEquals(1, channel.outboundMessages().size(), "every held head waits behind the streaming body");

		channel.resume();
		settle(channel);

		byte[] wire = drained(channel);
		String firstHead = headOf(wire);
		assertTrue(firstHead.startsWith("HTTP/1.1 200 OK\r\n"), firstHead);
		assertEquals(NetUtils.PIPELINE_MAX_REQUESTS, countOccurrences(wire, "HTTP/1.1 404 Not Found\r\n"), "every held head is answered once the body finishes");
		assertTrue(channel.isOpen(), "serving the held pipeline keeps the connection alive");
	}

	/** The held cap is a tripwire, not a welcome: junk past 512 KiB accumulated behind a streaming body closes the connection. */
	@Test
	void junkPastTheHeldCapWhileABodyStreamsClosesTheConnection() throws Exception {
		byte[] body = new byte[NetUtils.STREAM_WRITE_BYTES * 3];
		Path object = tempDir.resolve("junk.bin");
		Files.write(object, body);
		String hash = HashUtils.sha1(body);
		NettyServer junkServer = new NettyServer() {
			@Override
			public Optional<Path> getPath(String requestKey) {
				return requestKey.equals(hash) ? Optional.of(object) : Optional.empty();
			}
		};
		HoldableChannel channel = holdableChannel(junkServer, "/objects/" + hash);

		byte[] junk = new byte[512 * 1024 + 1]; // one byte past the held cap the handler enforces behind a streaming body
		Arrays.fill(junk, (byte) 'a');
		channel.writeInbound(Unpooled.wrappedBuffer(junk));
		channel.runPendingTasks();
		assertFalse(channel.isOpen(), "junk past the held cap must close the connection");
	}

	/** Counts occurrences of a US-ASCII needle in the raw response bytes. */
	private static int countOccurrences(byte[] wire, String needle) {
		int count = 0;
		for (int index = 0; index + needle.length() <= wire.length; index++) {
			if (wire[index] != needle.charAt(0)) continue;
			boolean matches = true;
			for (int offset = 1; offset < needle.length(); offset++) {
				if (wire[index + offset] != needle.charAt(offset)) {
					matches = false;
					break;
				}
			}
			if (matches) count++;
		}
		return count;
	}

	/** An embedded channel whose writability the test holds, so a streaming body can be stalled mid-drain. */
	private static final class HoldableChannel extends EmbeddedChannel {
		private volatile boolean writable = true;

		HoldableChannel(ChannelHandler handler) {
			super(handler);
		}

		@Override
		public boolean isWritable() {
			return writable;
		}

		/** Starts the drain again, as the socket's writability change would. */
		void resume() {
			writable = true;
			pipeline().fireChannelWritabilityChanged();
		}
	}

	/**
	 * Starts an identity body wide enough to span several writes, then stalls the drain before any body byte leaves:
	 * only the response head is out and the channel accumulates whatever is pipelined next.
	 */
	private HoldableChannel holdableChannel(NettyServer server, String target) {
		HoldableChannel channel = new HoldableChannel(new HttpContractHandler(server, Runnable::run));
		channels.add(channel);
		channel.writable = false;
		channel.writeInbound(Unpooled.wrappedBuffer(request(target).getBytes(StandardCharsets.UTF_8)));
		channel.runPendingTasks();
		assertEquals(1, channel.outboundMessages().size(), "only the response head may be out while the drain is stalled");
		assertTrue(((ByteBuf) channel.outboundMessages().peek()).toString(StandardCharsets.UTF_8).startsWith("HTTP/1.1 200 OK\r\n"));
		return channel;
	}

	/** The stall fuse: a client that stops draining mid-response finds its connection closed inside the stall window. */
	@Test
	void aStoppedDrainerIsClosedByTheStallFuse() throws Exception {
		byte[] body = new byte[NetUtils.WIRE_CHUNK_BYTES * 2 + 1024];
		Path object = tempDir.resolve("stall.bin");
		Files.write(object, body);
		ExecutorService readers = Executors.newFixedThreadPool(2);
		class SlowChannel extends EmbeddedChannel {
			volatile boolean writable = true;
			SlowChannel(ChannelHandler handler) {
				super(handler);
			}

			@Override
			public boolean isWritable() {
				return writable;
			}
		}
		// One-second fuse ticks against a three-second window: the channel stops being writable after the first
		// chunk, so the completed-write counter freezes and the fuse is the only thing left to act.
		NettyServer stallServer = new NettyServer() {
			@Override
			public Optional<Path> getPath(String requestKey) {
				return HashUtils.isSha1(requestKey) ? Optional.of(object) : Optional.empty();
			}
		};
		SlowChannel channel = new SlowChannel(new HttpContractHandler(stallServer, readers, 1, 3));
		channels.add(channel);
		channel.writeInbound(Unpooled.wrappedBuffer(request("/objects/" + HashUtils.sha1(body)).getBytes(StandardCharsets.UTF_8)));
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (channel.outboundMessages().size() < 2 && System.nanoTime() < deadline) channel.runPendingTasks();
		assertTrue(channel.outboundMessages().size() >= 2, "the head and the first chunk must stream before the stall");
		channel.writable = false; // the peer stops draining here: no buffer ever completes writing again
		long realDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (channel.isActive() && System.nanoTime() < realDeadline) {
			channel.runScheduledPendingTasks();
			channel.runPendingTasks();
			Thread.sleep(100);
		}
		assertFalse(channel.isActive(), "the stall fuse must close the connection");
		readers.shutdownNow();
	}

	/** Runs loop tasks until a full pass moves nothing: a streamed response unwinds over several task rounds. */
	private static void settle(EmbeddedChannel channel) {
		int previous = -1;
		while (true) {
			channel.runPendingTasks();
			int size = channel.outboundMessages().size();
			if (size == previous) return;
			previous = size;
		}
	}

	private static String exchange(EmbeddedChannel channel, String request) {
		channel.writeInbound(Unpooled.wrappedBuffer(request.getBytes(StandardCharsets.UTF_8)));
		settle(channel);
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
		// Long enough that compression visibly pays; the contract itself no longer cares about ratios.
		byte[] bytes = "object-payload-that-compresses-well\n".repeat(512).getBytes(StandardCharsets.UTF_8);
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
		fields.categories = Map.of("General", Map.of("main", group));
		ModpackCandidate candidate = new ModpackCandidate(GroupManifestValidator.validate(fields), new TreeMap<>(Map.of(hash, new StagedObject(hash, bytes.length, staged))), new TreeMap<>(), List.of());
		GenerationStore.Publication publication = store.publish(candidate, "");
		server = new NettyServer();
		server.replacePaths(publication.hostingPaths());
		return new Fixture(hash, new String(bytes, StandardCharsets.UTF_8), tempDir.resolve("host-generations").resolve("current-projection.json"),
				tempDir.resolve("host-generations").resolve("journal.jsonl"));
	}

	private record Fixture(String objectHash, String objectContent, Path headPath, Path journalPath) {}
}
