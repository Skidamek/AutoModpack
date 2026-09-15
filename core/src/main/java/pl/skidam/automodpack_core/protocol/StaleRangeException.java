package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

/**
 * The stored partial starts at or past the served object's end: the answer to a valid range request the object can no
 * longer satisfy. The partial is worthless and the retry must start from zero.
 */
public class StaleRangeException extends IOException {

	/** The wire vocabulary both ends share: FileSend answers such a range with exactly this error text. */
	public static final String WIRE_MESSAGE = "Invalid range";

	public StaleRangeException() {
		super("The stored partial starts past the end of the served object");
	}
}
