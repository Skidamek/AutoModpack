package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.NETWORK_TIMEOUT_MILLIS;
import static pl.skidam.automodpack_core.protocol.NetUtils.USER_AGENT;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.net.ssl.SSLSocket;

/**
 * One parked candidate's heartbeat while the human decides on certificate trust: every interval it writes a plain
 * {@code GET /head} - the head document is small JSON, and the response is consumed by its framing (Content-Length,
 * chunked, or close) whatever the status, so a ranged byte saves nothing worth the special case - and idle NAT mappings
 * and relay bindings never decay under the parked connection. It self-retires on the trust decision, a dead socket, or
 * a closed client; the write gate makes retirement wait for an in-flight heartbeat, including its consumed response, so
 * the connection is always byte-aligned when the trust decision hands it over. A failed beat closes the socket so a
 * half-read response cannot be handed to {@link Connection}.
 */
final class PreConfigurationKeepalive {

	private final SSLSocket socket;
	private final BooleanSupplier alive;
	private final ScheduledFuture<?> task;
	private final byte[] heartbeat;
	private final BufferedInputStream in;
	private final Object writeGate = new Object();
	private boolean retired;

	PreConfigurationKeepalive(SSLSocket socket, String hostHeader, String secret, Duration interval, ScheduledExecutorService executor, BooleanSupplier alive) throws IOException {
		this.socket = socket;
		this.alive = alive;
		// The Authorization header rides the heartbeat too: a host with validateSecrets on answers an unauthenticated heartbeat with a 401 and a close, killing the connection the heartbeat exists to keep alive.
		String authorization = secret == null ? "" : "Authorization: Bearer " + secret + "\r\n";
		this.heartbeat = ("GET /head HTTP/1.1\r\nHost: " + hostHeader + "\r\nUser-Agent: " + USER_AGENT + "\r\n" + authorization + "\r\n")
				.getBytes(StandardCharsets.UTF_8);
		this.in = new BufferedInputStream(socket.getInputStream());
		this.task = executor.scheduleWithFixedDelay(this::tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	private void tick() {
		boolean dead;
		synchronized (writeGate) {
			dead = retired || !alive.getAsBoolean() || socket.isClosed();
			if (!dead) dead = !beat();
			if (dead) retired = true;
		}
		if (dead) retireTask();
	}

	private boolean beat() {
		int previousTimeout = -1;
		try {
			OutputStream out = socket.getOutputStream();
			out.write(heartbeat);
			out.flush();
			// The server answers every heartbeat, so the response must be consumed before the next request or the first
			// application read would misframe against a stale response record. The bounded read can never wedge the scheduler.
			previousTimeout = socket.getSoTimeout();
			socket.setSoTimeout(NETWORK_TIMEOUT_MILLIS);
			discardResponse();
			return true;
		} catch (IOException died) {
			NetUtils.closeQuietly(socket);
			return false;
		} finally {
			if (previousTimeout >= 0) {
				try {
					socket.setSoTimeout(previousTimeout);
				} catch (IOException ignored) {
				}
			}
		}
	}

	private void discardResponse() throws IOException {
		HttpHead head = HttpHead.read(in);
		if (head.status() == 204 || head.status() == 304) return;
		if (chunked(head.headerValue("transfer-encoding"))) {
			drainChunked();
			return;
		}
		String lengthValue = head.headerValue("content-length");
		if (lengthValue != null) {
			drainLength(parseContentLength(lengthValue));
			return;
		}
		// Close-framed: the body ends at EOF and spends the parked socket. Drain it so nothing is left for Connection
		// to parse as the first response, then close so the handoff cannot reuse a spent lane.
		drainToEof();
		NetUtils.closeQuietly(socket);
	}

	private static boolean chunked(String transferEncoding) throws IOException {
		if (transferEncoding == null) return false;
		boolean chunked = false;
		for (String token : transferEncoding.split(",")) {
			String coding = token.trim().toLowerCase(Locale.ROOT);
			if (coding.isEmpty() || coding.equals("identity")) continue;
			if (!coding.equals("chunked")) throw new IOException("Unsupported keepalive Transfer-Encoding: " + transferEncoding);
			chunked = true;
		}
		return chunked;
	}

	private static long parseContentLength(String value) throws IOException {
		try {
			long length = Long.parseLong(value);
			if (length < 0) throw new NumberFormatException();
			return length;
		} catch (NumberFormatException e) {
			throw new IOException("Unparseable keepalive Content-Length: " + value);
		}
	}

	private void drainLength(long remaining) throws IOException {
		while (remaining > 0) {
			long skipped = in.skip(remaining);
			if (skipped <= 0) {
				if (in.read() < 0) throw new IOException("Keepalive response ended before its Content-Length");
				skipped = 1;
			}
			remaining -= skipped;
		}
	}

	private void drainChunked() throws IOException {
		while (true) {
			String line = HttpHead.readLine(in);
			String hex = line.indexOf(';') >= 0 ? line.substring(0, line.indexOf(';')).trim() : line.trim();
			long size;
			try {
				size = Long.parseLong(hex, 16);
				if (size < 0) throw new NumberFormatException();
			} catch (NumberFormatException e) {
				throw new IOException("Unparseable keepalive chunk size: " + line);
			}
			if (size == 0) {
				while (!HttpHead.readLine(in).isEmpty()) {
				}
				return;
			}
			drainLength(size);
			if (in.read() != '\r' || in.read() != '\n') throw new IOException("Keepalive chunked body is missing a chunk terminator");
		}
	}

	private void drainToEof() throws IOException {
		byte[] buffer = new byte[8192];
		while (in.read(buffer) >= 0) {
		}
	}

	/** Stops the heartbeat and returns only after any in-flight heartbeat, response consumption included, has finished. */
	void retire() {
		synchronized (writeGate) {
			retired = true;
		}
		retireTask();
	}

	private void retireTask() {
		task.cancel(false);
	}
}
