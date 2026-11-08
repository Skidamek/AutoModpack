package pl.skidam.automodpack_core.protocol;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** One debug line per rare wire transition; the receipts a silent wedge investigation needs. */
public final class WireTrace {
	private static final Logger LOGGER = LogManager.getLogger(WireTrace.class);

	private WireTrace() {}

	public static void log(String event, Object... kv) {
		if (!LOGGER.isDebugEnabled()) return;
		StringBuilder line = new StringBuilder(128).append("[wire] ").append(event);
		for (int i = 0; i + 1 < kv.length; i += 2) line.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
		LOGGER.debug(line.toString());
	}
}
