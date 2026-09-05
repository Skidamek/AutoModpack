package pl.skidam.automodpack_loader_core.screen;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A complete request for presenting an operational client failure.
 *
 * <p>
 * The message is a translation key with arguments. The cause message is also kept as a diagnostic
 * detail for the error screen and the copy action. The stack trace stays in the copy action and log.
 * </p>
 */
public record FailureRequest(Throwable cause, String messageKey, List<Object> messageArguments, FailureCategory category,
		FailureDestination returnDestination, Runnable retryAction, List<String> diagnosticDetails) {

	public FailureRequest {
		Objects.requireNonNull(cause, "cause");
		if (Objects.requireNonNull(messageKey, "messageKey").isBlank()) throw new IllegalArgumentException("Failure message key is blank");
		Objects.requireNonNull(messageArguments, "messageArguments");
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(returnDestination, "returnDestination");
		Objects.requireNonNull(diagnosticDetails, "diagnosticDetails");
		messageArguments = List.copyOf(messageArguments);
		diagnosticDetails = List.copyOf(diagnosticDetails);
	}

	public static FailureRequest of(Throwable cause, String messageKey, FailureCategory category, FailureDestination returnDestination,
			Runnable retryAction, Object... messageArguments) {
		return new FailureRequest(cause, messageKey, Arrays.asList(messageArguments.clone()), category, returnDestination, retryAction, causeLines(cause));
	}

	public FailureRequest withDiagnosticDetails(String... details) {
		List<String> merged = new ArrayList<>(Arrays.asList(details.clone()));
		for (String line : diagnosticDetails) if (!merged.contains(line)) merged.add(line);
		return new FailureRequest(cause, messageKey, messageArguments, category, returnDestination, retryAction, merged);
	}

	private static List<String> causeLines(Throwable cause) {
		List<String> lines = new ArrayList<>();
		for (Throwable current = cause; current != null; current = current.getCause()) {
			String message = current.getMessage();
			if (message == null || message.isBlank()) continue;
			String line = current.getClass().getSimpleName() + ": " + message.strip();
			if (!lines.contains(line)) lines.add(line);
		}
		return List.copyOf(lines);
	}

	public static FailureRequest internal(Throwable cause) {
		return of(cause, "automodpack.error.internal", FailureCategory.INTERNAL, FailureDestination.CURRENT_SCREEN, null);
	}

	public Object[] translationArguments() {
		return messageArguments.toArray();
	}

	/** Returns diagnostic text on demand; it is never rendered as ordinary user-facing text. */
	public String diagnosticText() {
		StringWriter trace = new StringWriter();
		cause.printStackTrace(new PrintWriter(trace));
		String context = diagnosticDetails.isEmpty() ? "" : "\n" + String.join("\n", diagnosticDetails) + "\n";
		return "AutoModpack failure\nCategory: " + category.key() + "\nMessage key: " + messageKey + context + "\n" + trace;
	}
}
