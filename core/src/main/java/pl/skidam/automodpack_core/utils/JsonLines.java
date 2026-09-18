package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonParseException;

/**
 * Line-oriented JSON file mechanics shared by append-only journals: strict line parsing with torn-tail crash
 * recovery. A crash or power cut mid-append tears the final line, so the tolerant read truncates to the intact
 * prefix and restores a missing trailing newline; anything unparsable earlier is real corruption.
 */
public final class JsonLines {
	/** Unusable *content*, not a locked or missing file: callers may aside the evidence. Physical IO stays a plain {@link IOException}. */
	public static final class UnusableContentException extends IOException {
		private final int lineNumber;

		public UnusableContentException(int lineNumber, String message, Throwable cause) {
			super(message, cause);
			this.lineNumber = lineNumber;
		}

		public int lineNumber() {
			return lineNumber;
		}
	}

	private JsonLines() {}

	/**
	 * Reads every line of an append-only JSON lines file through {@code parser}. A {@link JsonParseException} on the
	 * final line is a torn write when {@code tolerateTornTail} is set and is repaired away; a parser
	 * {@link IllegalArgumentException} is an entry-contract break and is corruption anywhere, tail included.
	 */
	public static <T> List<T> read(Path file, String description, Function<String, T> parser, boolean tolerateTornTail) throws IOException {
		if (!Files.exists(file)) return List.of();
		byte[] bytes = Files.readAllBytes(file);
		List<T> values = new ArrayList<>();
		int intactBytes = bytes.length;
		int lineStart = 0;
		while (lineStart < bytes.length) {
			int lineEnd = lineStart;
			while (lineEnd < bytes.length && bytes[lineEnd] != '\n') lineEnd++;
			boolean finalLine = lineEnd == bytes.length;
			String line = new String(bytes, lineStart, lineEnd - lineStart, StandardCharsets.UTF_8);
			int droppedFrom = lineStart;
			lineStart = finalLine ? bytes.length : lineEnd + 1;
			if (line.isBlank()) continue;
			try {
				values.add(parser.apply(line));
			} catch (IllegalArgumentException e) {
				// A line that parses as JSON but breaks the entry contract is real corruption, never a torn write - even at the tail.
				throw new UnusableContentException(values.size() + 1, "Corrupt " + description + " line " + (values.size() + 1) + " in " + file, e);
			} catch (JsonParseException e) {
				if (!finalLine || !tolerateTornTail) throw new UnusableContentException(values.size() + 1, "Malformed " + description + " line " + (values.size() + 1) + " in " + file, e);
				LOGGER.warn("{} {} ends in a torn line after {} intact entries; dropping the last {} bytes and keeping the intact prefix", description, file, values.size(), bytes.length - droppedFrom);
				truncate(file, droppedFrom);
				intactBytes = droppedFrom;
				break;
			}
		}
		if (tolerateTornTail && intactBytes > 0 && bytes[intactBytes - 1] != '\n') {
			// A crash can land after the entry's bytes but before its newline; the entry parsed fine, so restore the newline before the next append fuses two entries into one line.
			Files.writeString(file, "\n", StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
		}
		return List.copyOf(values);
	}

	/** Repairs the torn tail away, so later appends and the served file start from the intact prefix. */
	private static void truncate(Path file, long intactBytes) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.truncate(intactBytes);
			channel.force(true);
		}
	}
}
