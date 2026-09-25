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
		assertEquals(WireCodec.ZSTD, WireCodec.negotiate("zstd, gzip"));
		assertEquals(WireCodec.GZIP, WireCodec.negotiate("br, gzip, identity"));
		assertNull(WireCodec.negotiate("snappy, identity"));
		assertNull(WireCodec.negotiate("identity"));
		assertNull(WireCodec.negotiate(null));
		assertEquals("zstd, gzip", WireCodec.offeredEncodings());
	}

	@Test
	void negotiationMatchesTokensExactlyAndHonorsZeroQuality() {
		// Token-exact: a name that merely contains a wire name is unknown, not a match.
		assertNull(WireCodec.negotiate("zstandard"));
		assertNull(WireCodec.negotiate("x-gzip"));
		// q=0 excludes the coding; another listed codec still wins.
		assertNull(WireCodec.negotiate("zstd;q=0"));
		assertNull(WireCodec.negotiate("gzip;Q=0, zstd;q=0"));
		assertEquals(WireCodec.GZIP, WireCodec.negotiate("zstd;q=0, gzip"));
		assertEquals(WireCodec.ZSTD, WireCodec.negotiate("zstd;q=0.001"));
		assertEquals(WireCodec.ZSTD, WireCodec.negotiate("ZSTD"));
		// An unparseable q excludes too: never send what cannot be ruled in.
		assertNull(WireCodec.negotiate("zstd;q=maybe"));
	}
}
