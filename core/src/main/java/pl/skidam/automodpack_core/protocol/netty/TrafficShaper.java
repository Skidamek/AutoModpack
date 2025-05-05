package pl.skidam.automodpack_core.protocol.netty;

import io.netty.handler.traffic.GlobalTrafficShapingHandler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.GlobalVariables.serverConfig;

public class TrafficShaper {

    private final GlobalTrafficShapingHandler trafficShapingHandler;
    private ScheduledExecutorService executor = null;
    public static TrafficShaper trafficShaper;

    public TrafficShaper(ScheduledExecutorService executor) {
        close(); // There can be only one traffic shaper instance, close previous one if exists
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor();
            this.executor = executor;
        }

        long bandwidthLimit = serverConfig.bandwidthLimit * 1024L * 1024L / 8L;
        if (bandwidthLimit < 0) {
            bandwidthLimit = 0;
            LOGGER.warn("Invalid configured bandwidth limit ({} Mbps). Setting effective limit to 0 (unlimited).", serverConfig.bandwidthLimit);
        } else if (bandwidthLimit > 0) {
            LOGGER.info("Setting bandwidth limit to {} Mbps.", serverConfig.bandwidthLimit);
        }

        this.trafficShapingHandler = new GlobalTrafficShapingHandler(executor, bandwidthLimit, 0);
        TrafficShaper.trafficShaper = this;
    }

    public GlobalTrafficShapingHandler getTrafficShapingHandler() {
        return this.trafficShapingHandler;
    }

    public ScheduledExecutorService getExecutor() {
        return this.executor;
    }

    public static void close() {
        if (TrafficShaper.trafficShaper != null) {
            TrafficShaper.trafficShaper.getTrafficShapingHandler().release();
            if (TrafficShaper.trafficShaper.getExecutor() != null) {
                TrafficShaper.trafficShaper.getExecutor().close();
            }
            TrafficShaper.trafficShaper = null;
        }
    }
}
