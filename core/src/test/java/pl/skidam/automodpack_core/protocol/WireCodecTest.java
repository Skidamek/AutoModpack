package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import org.junit.jupiter.api.Test;

class WireCodecTest {

	@Test
	void everyCodecRoundTripsThroughItsOwnStreamPair() throws Exception {
		byte[] payload = new byte[1 << 20];
		new Random(42).nextBytes(payload);
		for (WireCodec codec : WireCodec.values()) {
			ByteArrayOutputStream sink = new ByteArrayOutputStream();
			try (var out = codec.wrap(sink)) {
				for (int offset = 0; offset < payload.length; offset += 65536) {
					out.write(payload, offset, Math.min(65536, payload.length - offset));
				}
			}
			byte[] decoded;
			try (var in = codec.unwrap(new ByteArrayInputStream(sink.toByteArray()))) {
				decoded = in.readAllBytes();
			}
			assertArrayEquals(payload, decoded, codec.wireName() + " round trip");
		}
	}

	@Test
	void negotiationTakesTheFirstRegistryCodecTheClientListed() {
		assertEquals(WireCodec.ZSTD, WireCodec.negotiate("zstd, snappy, gzip"));
		assertEquals(WireCodec.SNAPPY, WireCodec.negotiate("snappy, gzip"));
		assertEquals(WireCodec.GZIP, WireCodec.negotiate("br, gzip, identity"));
		assertNull(WireCodec.negotiate("identity"));
		assertNull(WireCodec.negotiate(null));
		assertEquals("zstd, snappy, gzip", WireCodec.offeredEncodings());
	}
}
