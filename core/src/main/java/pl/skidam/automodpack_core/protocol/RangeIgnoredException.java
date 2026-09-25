package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

/** The host answered a bounded take with a 200 whose declared length is past the slice: it ignores Range, and retrying any bounded take on it cannot change that. */
public class RangeIgnoredException extends IOException {

	public RangeIgnoredException(String path) {
		super("The server ignores Range requests; it answered the full body for " + path);
	}
}
