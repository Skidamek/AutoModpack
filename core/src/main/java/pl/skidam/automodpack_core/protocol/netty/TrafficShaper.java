package pl.skidam.automodpack_core.protocol.netty;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.serverConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import io.netty.handler.traffic.GlobalTrafficShapingHandler;

import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;

/** The one server-wide bandwidth shaper; every hosted connection pipeline installs its handler. */
public final class TrafficShaper {

	private static TrafficShaper instance;

	private final GlobalTrafficShapingHandler handler;
	private final ScheduledExecutorService ownedExecutor;

	private TrafficShaper(ScheduledExecutorService executor, ScheduledExecutorService ownedExecutor) {
		long bandwidthLimit = serverConfig.bandwidthLimit * 1024L * 1024L / 8L;
		if (bandwidthLimit < 0) {
			bandwidthLimit = 0;
			LOGGER.warn("Invalid configured bandwidth limit ({} Mbps). Setting effective limit to 0 (unlimited).", serverConfig.bandwidthLimit);
		} else if (bandwidthLimit > 0) { LOGGER.info("Setting bandwidth limit to {} Mbps.", serverConfig.bandwidthLimit); }
		this.handler = new GlobalTrafficShapingHandler(executor, bandwidthLimit, 0);
		this.ownedExecutor = ownedExecutor;
	}

	/** Starts the one shaper on the given event loop group, replacing any previous one. */
	public static void start(ScheduledExecutorService executor) {
		close();
		instance = new TrafficShaper(executor, null);
	}

	/** Starts the one shaper on its own scheduler thread, for hosting modes that own no event loop group. */
	public static void startShared() {
		close();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Traffic Shaper #%d").setDaemon(true).build());
		instance = new TrafficShaper(executor, executor);
	}

	public static GlobalTrafficShapingHandler handler() {
		TrafficShaper shaper = instance;
		// Crash with the cause instead of an NPE from a dereference: a pipeline install racing stop()
		// must read as "the shaper is not running", not as a null the reader has to chase.
		if (shaper == null) throw new IllegalStateException("Traffic shaper is not running");
		return shaper.handler;
	}

	public static void close() {
		if (instance != null) {
			instance.handler.release();
			if (instance.ownedExecutor != null) instance.ownedExecutor.shutdown();
			instance = null;
		}
	}
}
