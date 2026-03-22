package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohNode;
import java.io.IOException;

public final class IrohAvailability {
    private IrohAvailability() {
    }

    public static boolean isAvailable() {
        return IrohNode.isNativeAvailable();
    }

    public static String failureMessage() {
        String message = IrohNode.getNativeLoadError();
        return (message == null || message.isBlank())
                ? "AutoModpack iroh transport disabled: native library is unavailable."
                : message;
    }

    public static boolean warnIfUnavailable(org.apache.logging.log4j.Logger logger) {
        if (isAvailable()) {
            return true;
        }
        logger.warn(failureMessage());
        return false;
    }

    public static void requireAvailable() throws IOException {
        if (!isAvailable()) {
            throw new IOException(failureMessage());
        }
    }
}
