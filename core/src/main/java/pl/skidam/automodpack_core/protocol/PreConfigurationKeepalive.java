package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_KEEPALIVE_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.LATEST_SUPPORTED_PROTOCOL_VERSION;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.net.ssl.SSLSocket;

/**
 * One parked candidate's heartbeat while the human decides on certificate trust: every interval it writes a
 * configuration-phase keepalive the server absorbs silently, so idle NAT mappings and relay bindings never
 * decay under the parked connection. It self-retires on the trust decision, a dead socket, or a closed
 * client; the write gate makes retirement wait for an in-flight keepalive write.
 */
final class PreConfigurationKeepalive {

	private final SSLSocket socket;
	private final BooleanSupplier alive;
	private final ScheduledFuture<?> task;
	private final Object writeGate = new Object();
	private boolean retired;

	PreConfigurationKeepalive(SSLSocket socket, Duration interval, ScheduledExecutorService executor, BooleanSupplier alive) {
		this.socket = socket;
		this.alive = alive;
		this.task = executor.scheduleWithFixedDelay(this::tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	private void tick() {
		boolean dead;
		synchronized (writeGate) {
			dead = retired || !alive.getAsBoolean() || socket.isClosed();
			if (!dead) {
				try {
					OutputStream out = socket.getOutputStream();
					out.write(new byte[]{LATEST_SUPPORTED_PROTOCOL_VERSION, CONFIGURATION_KEEPALIVE_TYPE});
					out.flush();
				} catch (IOException died) {
					dead = true;
				}
			}
			if (dead) retired = true;
		}
		if (dead) retireTask();
	}

	/** Stops the heartbeat and returns only after any in-flight keepalive write has finished. */
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
