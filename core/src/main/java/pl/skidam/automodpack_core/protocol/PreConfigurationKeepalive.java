package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.NETWORK_TIMEOUT_MILLIS;
import static pl.skidam.automodpack_core.protocol.NetUtils.USER_AGENT;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.net.ssl.SSLSocket;

/**
 * One parked candidate's heartbeat while the human decides on certificate trust: every interval it writes a one-byte
 * ranged {@code GET /head} the server answers with a 206, so idle NAT mappings and relay bindings never decay under
 * the parked connection. It self-retires on the trust decision, a dead socket, or a closed client; the write gate
 * makes retirement wait for an in-flight heartbeat, including its consumed response, so the connection is always
 * byte-aligned when the trust decision hands it over.
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
		this.heartbeat = ("GET /head HTTP/1.1\r\nHost: " + hostHeader + "\r\nUser-Agent: " + USER_AGENT + "\r\n" + authorization + "Range: bytes=0-0\r\n\r\n")
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
			// application read would misframe against a stale 206. The bounded read can never wedge the scheduler.
			previousTimeout = socket.getSoTimeout();
			socket.setSoTimeout(NETWORK_TIMEOUT_MILLIS);
			discardResponse();
			return true;
		} catch (IOException died) {
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
		String lengthValue = HttpHead.read(in).headerValue("content-length");
		if (lengthValue == null) throw new IOException("Keepalive response without a Content-Length");
		long contentLength;
		try {
			contentLength = Long.parseLong(lengthValue);
		} catch (NumberFormatException e) {
			throw new IOException("Unparseable keepalive response: " + lengthValue);
		}
		for (long remaining = contentLength; remaining > 0;) {
			long skipped = in.skip(remaining);
			if (skipped <= 0) {
				if (in.read() < 0) throw new IOException("Keepalive response ended before its Content-Length");
				skipped = 1;
			}
			remaining -= skipped;
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
