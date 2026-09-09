package pl.skidam.automodpack_core.utils;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Small throwable algebra shared by the network, storage and UI layers. */
public final class Throwables {
	private Throwables() {}

	public static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
		Throwable current = throwable;
		while (current != null) {
			if (type.isInstance(current)) return type.cast(current);
			current = current.getCause();
		}
		return null;
	}

	public static Throwable unwrap(Throwable throwable) {
		Throwable current = throwable;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	/** The failure's own words when it has any; the class name is only the last resort. */
	public static String detail(Throwable throwable) {
		return throwable.getMessage() == null || throwable.getMessage().isBlank() ? throwable.getClass().getSimpleName() : throwable.getMessage();
	}
}
