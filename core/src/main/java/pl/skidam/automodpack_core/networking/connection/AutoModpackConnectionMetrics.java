package pl.skidam.automodpack_core.networking.connection;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public final class AutoModpackConnectionMetrics {
    private static final LongAdder FRAMES_SENT = new LongAdder();
    private static final LongAdder FRAMES_RECEIVED = new LongAdder();
    private static final LongAdder FRAMES_BYPASSED_MINECRAFT_COMPRESSION = new LongAdder();
    private static final LongAdder FLUSH_COUNT = new LongAdder();
    private static final LongAdder QUEUE_DRAINS = new LongAdder();
    private static final LongAdder BYTES_SENT = new LongAdder();
    private static final LongAdder BYTES_RECEIVED = new LongAdder();
    private static final long DEBUG_LOG_THRESHOLD_NANOS = 1_000_000L;

    private AutoModpackConnectionMetrics() {}

    public static void recordFrameSent(int bytes) {
        FRAMES_SENT.increment();
        FRAMES_BYPASSED_MINECRAFT_COMPRESSION.increment();
        BYTES_SENT.add(bytes);
    }

    public static void recordFrameReceived(int bytes) {
        FRAMES_RECEIVED.increment();
        FRAMES_BYPASSED_MINECRAFT_COMPRESSION.increment();
        BYTES_RECEIVED.add(bytes);
    }

    public static void recordFlush() {
        FLUSH_COUNT.increment();
    }

    public static void recordQueueDrain() {
        QUEUE_DRAINS.increment();
    }

    public static long startDebugTimer() {
        return LOGGER.isDebugEnabled() ? System.nanoTime() : 0L;
    }

    public static void logDebugDuration(String operation, long startedAt) {
        if (startedAt == 0L) {
            return;
        }

        long elapsedNanos = System.nanoTime() - startedAt;
        if (elapsedNanos >= DEBUG_LOG_THRESHOLD_NANOS) {
            LOGGER.debug("{} took {} ms", operation, String.format(Locale.ROOT, "%.3f", elapsedNanos / 1_000_000.0d));
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            FRAMES_SENT.sum(),
            FRAMES_RECEIVED.sum(),
            FRAMES_BYPASSED_MINECRAFT_COMPRESSION.sum(),
            FLUSH_COUNT.sum(),
            QUEUE_DRAINS.sum(),
            BYTES_SENT.sum(),
            BYTES_RECEIVED.sum()
        );
    }

    public record Snapshot(
        long framesSent,
        long framesReceived,
        long framesBypassedMinecraftCompression,
        long flushCount,
        long queueDrains,
        long bytesSent,
        long bytesReceived
    ) {}
}
