package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One HTTP/1.x response head read off a stream: the status code, whether the peer spoke HTTP/1.0, and the raw header
 * block. Every reader of this wire shares the bounds - a header line past 8 KiB or a head past 128 lines (interim
 * heads included) is a hostile or broken peer and fails the connection.
 */
final class HttpHead {
	private static final int MAX_LINE_BYTES = 8 * 1024;
	private static final int MAX_LINES = 128;

	private final int status;
	private final boolean http10;
	private final List<Header> headers;

	private HttpHead(int status, boolean http10, List<Header> headers) {
		this.status = status;
		this.http10 = http10;
		this.headers = headers;
	}

	int status() {
		return status;
	}

	boolean http10() {
		return http10;
	}

	/** The last value of the given lower-cased header name, or null when absent. */
	String headerValue(String lowerName) {
		String value = null;
		for (Header header : headers) if (header.name().equals(lowerName)) value = header.value();
		return value;
	}

	static HttpHead read(InputStream in) throws IOException {
		int budget = MAX_LINES;
		while (true) {
			String statusLine = readLine(in);
			if (--budget < 0) throw new IOException("Response head exceeded " + MAX_LINES + " lines");
			if (!statusLine.startsWith("HTTP/1.1 ") && !statusLine.startsWith("HTTP/1.0 ")) throw new IOException("Not an HTTP/1.1 response: " + statusLine);
			boolean http10 = statusLine.startsWith("HTTP/1.0 ");
			int status;
			try {
				status = Integer.parseInt(statusLine.split(" ", 3)[1]);
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				throw new IOException("Unparseable HTTP status line: " + statusLine);
			}
			if (status == 101) throw new IOException("HTTP 101 protocol upgrade is not this wire: " + statusLine);
			if (status >= 100 && status < 200) {
				// RFC 9110 §15.2: an interim (1xx) head is never the response - drain its header block and read the real one.
				budget = drainHeaderBlock(in, budget);
				continue;
			}
			List<Header> headers = new ArrayList<>();
			for (String line; !(line = readLine(in)).isEmpty();) {
				if (--budget < 0) throw new IOException("Response head exceeded " + MAX_LINES + " lines");
				int colon = line.indexOf(':');
				if (colon > 0) headers.add(new Header(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim()));
			}
			return new HttpHead(status, http10, headers);
		}
	}

	/** Drains an interim head's header block inside the shared line budget; returns the budget left. */
	private static int drainHeaderBlock(InputStream in, int budget) throws IOException {
		for (String line; !(line = readLine(in)).isEmpty();) {
			if (--budget < 0) throw new IOException("Response head exceeded " + MAX_LINES + " lines");
		}
		return budget;
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
