package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The status-line rules of the shared head reader: a 1.0 host parses, interim heads skip, an upgrade and strangers fail. */
class HttpHeadTest {

	@Test
	void anHttp10StatusLineParsesAndIsExposedAsHttp10() throws Exception {
		HttpHead head = HttpHead.read(new ByteArrayInputStream("HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
		assertEquals(200, head.status());
		assertTrue(head.http10());
	}

	@Test
	void anHttp11StatusLineIsNotExposedAsHttp10() throws Exception {
		HttpHead head = HttpHead.read(new ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
		assertEquals(200, head.status());
		assertFalse(head.http10());
	}

	@Test
	void anInterimHeadIsSkippedAndTheRealHeadIsRead() throws Exception {
		String wire = "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n";
		HttpHead head = HttpHead.read(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
		assertEquals(200, head.status());
		assertFalse(head.http10());
		assertEquals("2", head.headerValue("content-length"));
	}

	@Test
	void aProtocolUpgradeFailsLoudly() {
		assertThrows(IOException.class, () -> HttpHead.read(new ByteArrayInputStream("HTTP/1.1 101 Switching Protocols\r\n\r\n".getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void aForeignStatusLineFailsLoudly() {
		assertThrows(IOException.class, () -> HttpHead.read(new ByteArrayInputStream("HTTP/2 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8))));
	}

	/** The line budget counts across skipped interim heads, so a hostile peer cannot loop 1xx heads forever. */
	@Test
	void loopedInterimHeadsExhaustTheLineBudget() {
		StringBuilder wire = new StringBuilder();
		for (int i = 0; i < 500; i++) wire.append("HTTP/1.1 103 Early Hints\r\n\r\n");
		assertThrows(IOException.class, () -> HttpHead.read(new ByteArrayInputStream(wire.toString().getBytes(StandardCharsets.UTF_8))));
	}
}
