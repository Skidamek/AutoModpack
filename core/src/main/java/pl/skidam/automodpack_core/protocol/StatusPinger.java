package pl.skidam.automodpack_core.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Minimal vanilla Minecraft status ping (nextState=1) used to read a host's advertised protocol version. */
public final class StatusPinger {
	private static final int TIMEOUT_MILLIS = 3000;
	private static final int MAX_PACKET_BYTES = 64 * 1024;
	private static final int STATUS_STATE = 1;

	private StatusPinger() {}

	/** Returns the server's positive protocol number from a standard status ping, or empty when unreachable or negative. */
	public static OptionalInt fetchProtocolVersion(InetSocketAddress origin, int clientProtocol) {
		if (origin == null || origin.getPort() < 1 || origin.getPort() > 65535) return OptionalInt.empty();
		try (Socket socket = new Socket()) {
			socket.connect(origin, TIMEOUT_MILLIS);
			socket.setSoTimeout(TIMEOUT_MILLIS);
			socket.setTcpNoDelay(true);

			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			writePacket(out, buildHandshakePayload(clientProtocol, origin.getHostString(), origin.getPort()));
			// Status Request: packet id 0, no payload.
			out.writeByte(1);
			out.writeByte(0);
			out.flush();

			DataInputStream in = new DataInputStream(socket.getInputStream());
			int packetLen = readVarInt(in);
			if (packetLen <= 0 || packetLen > MAX_PACKET_BYTES) return OptionalInt.empty();
			int packetId = readVarInt(in);
			if (packetId != 0) return OptionalInt.empty();
			int jsonLen = readVarInt(in);
			if (jsonLen <= 0 || jsonLen > MAX_PACKET_BYTES) return OptionalInt.empty();
			byte[] jsonBytes = in.readNBytes(jsonLen);
			if (jsonBytes.length != jsonLen) return OptionalInt.empty();

			JsonElement root = JsonParser.parseString(new String(jsonBytes, StandardCharsets.UTF_8));
			if (!root.isJsonObject()) return OptionalInt.empty();
			JsonObject version = root.getAsJsonObject().getAsJsonObject("version");
			if (version == null || !version.has("protocol")) return OptionalInt.empty();
			int protocol = version.get("protocol").getAsInt();
			return protocol > 0 ? OptionalInt.of(protocol) : OptionalInt.empty();
		} catch (Exception e) {
			return OptionalInt.empty();
		}
	}

	private static byte[] buildHandshakePayload(int protocolVersion, String host, int port) throws IOException {
		ByteArrayOutputStream payload = new ByteArrayOutputStream(64);
		writeVarInt(payload, 0);
		writeVarInt(payload, protocolVersion);
		byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
		writeVarInt(payload, hostBytes.length);
		payload.write(hostBytes);
		payload.write((port >>> 8) & 0xFF);
		payload.write(port & 0xFF);
		writeVarInt(payload, STATUS_STATE);
		return payload.toByteArray();
	}

	private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
		ByteArrayOutputStream framed = new ByteArrayOutputStream(payload.length + 5);
		writeVarInt(framed, payload.length);
		framed.write(payload);
		out.write(framed.toByteArray());
	}

	static void writeVarInt(ByteArrayOutputStream out, int value) {
		int remaining = value;
		while ((remaining & ~0x7F) != 0) {
			out.write((remaining & 0x7F) | 0x80);
			remaining >>>= 7;
		}
		out.write(remaining);
	}

	static int readVarInt(DataInputStream in) throws IOException {
		int numRead = 0;
		int result = 0;
		byte read;
		do {
			int next = in.read();
			if (next < 0) throw new EOFException();
			read = (byte) next;
			result |= (read & 0x7F) << (7 * numRead);
			numRead++;
			if (numRead > 5) throw new IOException("VarInt too big");
		} while ((read & 0x80) != 0);
		return result;
	}
}
