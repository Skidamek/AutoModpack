package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohConnection;
import dev.iroh.IrohNode;
import dev.iroh.IrohRemoteAddressBook;

import java.nio.file.Path;

import static pl.skidam.automodpack_core.Constants.privateDir;

public final class IrohTransportSupport {
    public static final String ALPN = "automodpack/1";
    public static final Path IROH_KEY_FILE = privateDir.resolve("iroh.key");
    // Keep raw TCP neutral so direct IP can win naturally while minecraft remains disadvantaged.
    public static final long RAW_TCP_RTT_BIAS_MS = 0L;
    public static final long CONNECT_TIMEOUT_MS = 60_000L;
    public static final long PATH_SELECTION_TIMEOUT_MS = 60_000L;
    public static final long STREAM_OPEN_TIMEOUT_MS = 120_000L;
    public static final long STREAM_READ_TIMEOUT_MS = 300_000L;
    public static final long SESSION_WAIT_TIMEOUT_SECONDS = 300L;

    private IrohTransportSupport() {
    }

    public static IrohConnection connectWithRetries(IrohNode activeNode, IrohRemoteAddressBook addressBook, int attempts, long timeoutMs) {
        byte[] remoteId = addressBook.getRemoteId();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            pl.skidam.automodpack_core.Constants.LOGGER.info(
                    "Attempting iroh QUIC dial {}/{} for peer {}",
                    attempt,
                    attempts,
                    shortPeerId(remoteId)
            );
            IrohConnection candidate = activeNode.connect(addressBook, timeoutMs);
            if (candidate != null) {
                if (!waitForSelectedPath(candidate, PATH_SELECTION_TIMEOUT_MS)) {
                    pl.skidam.automodpack_core.Constants.LOGGER.warn(
                            "Iroh QUIC dial {}/{} did not select a path for peer {} within {}ms",
                            attempt,
                            attempts,
                            shortPeerId(remoteId),
                            PATH_SELECTION_TIMEOUT_MS
                    );
                    try {
                        candidate.close();
                    } catch (Exception ignored) {
                    }
                } else {
                    pl.skidam.automodpack_core.Constants.LOGGER.debug(
                            "Iroh QUIC dial {}/{} succeeded for peer {}",
                            attempt,
                            attempts,
                            shortPeerId(remoteId)
                    );
                    pl.skidam.automodpack_core.Constants.LOGGER.info("Iroh path snapshot after connect: {}", describePaths(candidate));
                    return candidate;
                }
            }

            pl.skidam.automodpack_core.Constants.LOGGER.warn(
                    "Iroh QUIC dial {}/{} failed for peer {}",
                    attempt,
                    attempts,
                    shortPeerId(remoteId)
            );
            if (attempt < attempts) {
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        return null;
    }

    public static IrohConnection connectWithRetries(IrohNode activeNode, byte[] remoteId, int attempts, long timeoutMs) {
        return connectWithRetries(activeNode, IrohRemoteAddressBook.of(remoteId, java.util.List.of(), activeNode.getCustomTransportIds()), attempts, timeoutMs);
    }

    private static String describePaths(IrohConnection connection) {
        return IrohPathSummary.describe(connection);
    }

    public static String shortPeerId(byte[] peerId) {
        String hex = java.util.HexFormat.of().formatHex(peerId);
        return hex.substring(0, Math.min(hex.length(), 16)) + "...";
    }

    private static boolean waitForSelectedPath(IrohConnection connection, long timeoutMs) {
        long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadlineNanos) {
            if (java.util.Arrays.stream(connection.getPaths()).anyMatch(dev.iroh.IrohPathInfo::isSelected)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return java.util.Arrays.stream(connection.getPaths()).anyMatch(dev.iroh.IrohPathInfo::isSelected);
    }
}
