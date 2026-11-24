package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class StatusPingerTest {
	@Test
	void readsPositiveProtocolVersionFromStatusResponse() throws Exception {
		assertEquals(OptionalInt.of(763), pingWithResponse("{\"version\":{\"name\":\"1.20.1\",\"protocol\":763}}"));
	}

	@Test
	void ignoresNegativeProtocolVersion() throws Exception {
		assertTrue(pingWithResponse("{\"version\":{\"name\":\"e4mc\",\"protocol\":-1}}").isEmpty());
	}

	private static OptionalInt pingWithResponse(String json) throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			Thread responder = new Thread(() -> {
				try (Socket client = server.accept()) {
					DataInputStream in = new DataInputStream(client.getInputStream());
					int handshakeLen = StatusPinger.readVarInt(in);
					in.readNBytes(handshakeLen);
					int reqLen = StatusPinger.readVarInt(in);
					in.readNBytes(reqLen);

					byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
					ByteArrayOutputStream payload = new ByteArrayOutputStream();
					StatusPinger.writeVarInt(payload, 0);
					StatusPinger.writeVarInt(payload, jsonBytes.length);
					payload.write(jsonBytes);
					byte[] body = payload.toByteArray();

					ByteArrayOutputStream framed = new ByteArrayOutputStream();
					StatusPinger.writeVarInt(framed, body.length);
					framed.write(body);
					DataOutputStream out = new DataOutputStream(client.getOutputStream());
					out.write(framed.toByteArray());
					out.flush();
				} catch (Exception ignored) {
				}
			});
			responder.start();
			OptionalInt result = StatusPinger.fetchProtocolVersion(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 767);
			responder.join(3000);
			return result;
		}
	}
}
