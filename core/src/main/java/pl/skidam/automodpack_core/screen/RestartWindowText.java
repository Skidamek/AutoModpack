package pl.skidam.automodpack_core.screen;

import java.util.Objects;

/**
 * The restart window's text, resolved before the window exists. The window process only renders what the caller
 * decided, so it never reads an options file or a language key of its own.
 */
public record RestartWindowText(String title, String heading, String confirm, String message) {
	public RestartWindowText {
		title = Objects.requireNonNull(title, "title");
		heading = Objects.requireNonNull(heading, "heading");
		confirm = Objects.requireNonNull(confirm, "confirm");
		message = Objects.requireNonNull(message, "message");
	}
}
