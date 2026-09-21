package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One HTTP/1.1 response head read off a stream: the status code and the raw header block. Every reader of this wire
 * shares the bounds - a header line past 8 KiB or a block past 128 lines is a hostile or broken peer and fails the
 * connection.
 */
final class HttpHead {
	private static final int MAX_LINE_BYTES = 8 * 1024;
	private static final int MAX_LINES = 128;

	private final int status;
	private final List<Header> headers;

	private HttpHead(int status, List<Header> headers) {
		this.status = status;
		this.headers = headers;
	}

	int status() {
		return status;
	}

	/** The last value of the given lower-cased header name, or null when absent. */
	String headerValue(String lowerName) {
		String value = null;
		for (Header header : headers) if (header.name().equals(lowerName)) value = header.value();
		return value;
	}

	static HttpHead read(InputStream in) throws IOException {
		String statusLine = readLine(in);
		if (!statusLine.startsWith("HTTP/1.1 ")) throw new IOException("Not an HTTP/1.1 response: " + statusLine);
		String[] parts = statusLine.split(" ", 3);
		int status;
		try {
			status = Integer.parseInt(parts[1]);
		} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
			throw new IOException("Unparseable HTTP status line: " + statusLine);
		}
		List<Header> headers = new ArrayList<>();
		for (int lines = 0; lines++ < MAX_LINES;) {
			String line = readLine(in);
			if (line.isEmpty()) return new HttpHead(status, headers);
			int colon = line.indexOf(':');
			if (colon <= 0) continue;
			headers.add(new Header(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim()));
		}
		throw new IOException("Response header block exceeded " + MAX_LINES + " lines");
	}

	/** Reads one CRLF-terminated line; TLS already framed the records, so only a hostile peer can stretch one. */
	static String readLine(InputStream in) throws IOException {
		StringBuilder line = new StringBuilder(64);
		int previous = -1;
		while (true) {
			int read = in.read();
			if (read < 0) throw new IOException("Connection ended inside a response header");
			if (previous == '\r' && read == '\n') return line.substring(0, line.length() - 1);
			line.append((char) read);
			if (line.length() > MAX_LINE_BYTES) throw new IOException("Response header line exceeded " + MAX_LINE_BYTES + " bytes");
			previous = read;
		}
	}

	private record Header(String name, String value) {}
}
