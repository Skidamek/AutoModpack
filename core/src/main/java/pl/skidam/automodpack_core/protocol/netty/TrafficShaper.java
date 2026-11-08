package pl.skidam.automodpack_core.protocol.netty;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import io.netty.handler.traffic.GlobalTrafficShapingHandler;

import pl.skidam.automodpack_core.config.ServerConfigStore;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;

/** Bandwidth shaper owned by one {@link NettyServer}: every hosted connection pipeline installs its handler. */
public final class TrafficShaper {

	private final GlobalTrafficShapingHandler handler;
	private final ScheduledExecutorService ownedExecutor;

	private TrafficShaper(ScheduledExecutorService executor, ScheduledExecutorService ownedExecutor) {
		long bandwidthLimit = ServerConfigStore.current().bandwidthLimit * 1024L * 1024L / 8L;
		if (bandwidthLimit < 0) {
			bandwidthLimit = 0;
			LOGGER.warn("Invalid configured bandwidth limit ({} Mbps). Setting effective limit to 0 (unlimited).", ServerConfigStore.current().bandwidthLimit);
		} else if (bandwidthLimit > 0) { LOGGER.info("Setting bandwidth limit to {} Mbps.", ServerConfigStore.current().bandwidthLimit); }
		this.handler = new GlobalTrafficShapingHandler(executor, bandwidthLimit, 0);
		this.ownedExecutor = ownedExecutor;
	}

	public static TrafficShaper on(ScheduledExecutorService executor) {
		return new TrafficShaper(executor, null);
	}

	public static TrafficShaper owned() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Traffic Shaper #%d").setDaemon(true).build());
		return new TrafficShaper(executor, executor);
	}

	public GlobalTrafficShapingHandler handler() {
		return handler;
	}

	public void close() {
		handler.release();
		if (ownedExecutor != null) ownedExecutor.shutdown();
	}
}
