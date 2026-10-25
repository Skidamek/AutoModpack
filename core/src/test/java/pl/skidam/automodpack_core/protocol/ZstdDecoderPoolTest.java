package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class ZstdDecoderPoolTest {

	private static byte[] payload(int seed, int size) {
		Random random = new Random(seed);
		byte[] data = new byte[size];
		String words = "automodpack zstd decoder pool reuse window ";
		for (int i = 0; i < size; i++) {
			data[i] = random.nextInt(8) == 0 ? (byte) random.nextInt(256) : (byte) words.charAt((i * 7 + seed) % words.length());
		}
		return data;
	}

	private static byte[] zstd(byte[] data) throws IOException {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		try (OutputStream out = WireCodec.ZSTD.wrap(sink)) {
			out.write(data);
		}
		return sink.toByteArray();
	}

	private static byte[] unzstd(byte[] frame) throws IOException {
		try (InputStream in = WireCodec.ZSTD.unwrap(new ByteArrayInputStream(frame))) {
			return in.readAllBytes();
		}
	}

	@Test
	void manySmallStreamsDecodeExactlyOnRecycledDecoders() throws Exception {
		for (int i = 0; i < 200; i++) {
			byte[] data = payload(i, 1 + i * 97);
			assertArrayEquals(data, unzstd(zstd(data)), "stream " + i);
		}
		assertTrue(ZstdDecoderPool.idle() >= 1 && ZstdDecoderPool.idle() <= ZstdDecoderPool.MAX_IDLE, "idle " + ZstdDecoderPool.idle());
	}

	@Test
	void openStreamsNeverShareADecoder() throws Exception {
		byte[] a = payload(1, 300_000);
		byte[] b = payload(2, 300_000);
		ByteArrayOutputStream outA = new ByteArrayOutputStream();
		ByteArrayOutputStream outB = new ByteArrayOutputStream();
		try (InputStream inA = WireCodec.ZSTD.unwrap(new ByteArrayInputStream(zstd(a)));
				InputStream inB = WireCodec.ZSTD.unwrap(new ByteArrayInputStream(zstd(b)))) {
			byte[] buffer = new byte[4096];
			boolean doneA = false;
			boolean doneB = false;
			while (!doneA || !doneB) {
				if (!doneA) {
					int n = inA.read(buffer);
					if (n < 0) doneA = true;
					else outA.write(buffer, 0, n);
				}
				if (!doneB) {
					int n = inB.read(buffer);
					if (n < 0) doneB = true;
					else outB.write(buffer, 0, n);
				}
			}
		}
		assertArrayEquals(a, outA.toByteArray());
		assertArrayEquals(b, outB.toByteArray());
	}

	@Test
	void concurrentStreamsEachGetTheirOwnBytes() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				int thread = t;
				futures.add(pool.submit(() -> {
					for (int i = 0; i < 60; i++) {
						byte[] data = payload(thread * 1000 + i, 500 + (i * 4099) % 200_000);
						assertArrayEquals(data, unzstd(zstd(data)), "thread " + thread + " stream " + i);
					}
					return null;
				}));
			}
			for (Future<?> future : futures) future.get();
		} finally {
			pool.shutdownNow();
		}
		assertTrue(ZstdDecoderPool.idle() <= ZstdDecoderPool.MAX_IDLE);
	}

	@Test
	void aCorruptStreamLeavesItsRecycledDecoderClean() throws Exception {
		byte[] data = payload(7, 50_000);
		byte[] frame = zstd(data);
		for (int round = 0; round < 20; round++) {
			byte[] corrupt = frame.clone();
			corrupt[10 + round * 37 % (corrupt.length - 10)] ^= (byte) 0x5A;
			try {
				unzstd(corrupt);
			} catch (IOException | RuntimeException expected) {
				// MalformedInputException (a RuntimeException) or an IOException, exactly as with a fresh decoder
			}
			assertArrayEquals(data, unzstd(frame), "clean stream after corrupt round " + round);
		}
		byte[] truncated = Arrays.copyOf(frame, frame.length / 2);
		assertThrows(Exception.class, () -> unzstd(truncated));
		assertArrayEquals(data, unzstd(frame));
	}

	@Test
	void aClosedStreamRefusesReadsAndClosingTwiceIsHarmless() throws Exception {
		InputStream in = WireCodec.ZSTD.unwrap(new ByteArrayInputStream(zstd("hello".getBytes(StandardCharsets.UTF_8))));
		assertEquals('h', in.read());
		in.close();
		in.close();
		assertThrows(IOException.class, in::read);
		assertThrows(IOException.class, () -> in.read(new byte[8], 0, 8));
		// the decoder went back exactly once: the next stream on it still decodes
		assertArrayEquals("again".getBytes(StandardCharsets.UTF_8), unzstd(zstd("again".getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void aBigStreamsDecoderIsLeftToTheGc() throws Exception {
		byte[] warm = payload(3, 1000);
		unzstd(zstd(warm));
		byte[] big = payload(4, (int) ZstdDecoderPool.RETAIN_DECODED_LIMIT * 4);
		byte[] frame = zstd(big);
		int before = ZstdDecoderPool.idle();
		assertArrayEquals(big, unzstd(frame));
		assertEquals(Math.max(0, before - 1), ZstdDecoderPool.idle(), "the big stream took a pooled decoder and did not return it");
	}

	@Test
	void aBlockHeaderInTheLastThreeBufferedBytesDecodes() throws Exception {
		// no checksum, a raw "hello" block, then an empty last raw block: the frame ends on a bare 3-byte block header,
		// which the bounds-checked decoder must not read past
		byte[] frame = {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0, 0, 0x28, 0, 0, 'h', 'e', 'l', 'l', 'o', 0x01, 0, 0};
		for (int i = 0; i < 3; i++) {
			assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), unzstd(frame));
		}
	}
}
