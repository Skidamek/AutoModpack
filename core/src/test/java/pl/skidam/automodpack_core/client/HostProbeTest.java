package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.protocol.HostTiles;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.DownloadScheduler;

/**
 * The host probe: who may lend a file's prefix to measure the host wire, what the measurement is booked against, and that
 * a platform already delivering is never disturbed.
 */
class HostProbeTest {

	private static final long MiB = 1024L * 1024L;
	private static final long SECOND = 1_000_000_000L;

	@TempDir
	Path tempDir;

	private DataRootResolver.Layout layout;
	private DownloadScheduler scheduler;
	private RescuePolicy rescue;
	private HostProbe probe;

	private void setUp() {
		layout = new DataRootResolver.Layout(tempDir.resolve("data"));
		scheduler = new DownloadScheduler();
		rescue = new RescuePolicy(scheduler);
		probe = new HostProbe(scheduler, rescue);
	}

	/** Reports a rate big enough to land an estimate outright, which is what the pooling floor otherwise withholds. */
	private void measure(String domain, double bytesPerSecond) {
		scheduler.report(domain, Math.round(bytesPerSecond), SECOND);
	}

	private QueuedDownload task(String name, long size, List<String> domains) {
		FileInspection.HashPathPair key = new FileInspection.HashPathPair(name.repeat(40).substring(0, 40), Path.of(name));
		QueuedDownload download = new QueuedDownload(key, Path.of(name), List.of(), null, null, size, 0, () -> {}, ignored -> {});
		download.domains.addAll(domains);
		return download;
	}

	@Test
	void eligibilityIsOneTableOverTheConditionsThatGateTheProbe() {
		setUp();
		DownloadData unmeasured = attempt("cdn.example.com");
		assertFalse(probe.worthMeasuring(unmeasured), "an unmeasured candidate means the source may still be the fast one");

		DownloadData slow = attempt("cdn.example.com");
		measure("cdn.example.com", 100 * 1024);
		assertTrue(probe.worthMeasuring(slow), "a measured candidate under the bar is the probe's reason to exist");

		measure("cdn.example.com", 50 * MiB);
		assertFalse(probe.worthMeasuring(attempt("cdn.example.com")), "a platform at the bar needs no rescue");

		measure("cdn.example.com", 100 * 1024);
		DownloadData dual = new DownloadData(Path.of("mod.jar"), "a".repeat(40), "cdn.example.com", 8 * MiB, List.of("cdn.example.com", "other.example.com"));
		assertFalse(probe.worthMeasuring(dual), "a second unmeasured candidate on the same file must be tried before moving traffic off the first");

		measure("other.example.com", 100 * 1024);
		assertTrue(probe.worthMeasuring(dual), "every candidate measured and under the bar is the probe's reason to exist");

		measure(RescuePolicy.HOST_DOMAIN, 50 * MiB);
		assertFalse(probe.worthMeasuring(attempt("cdn.example.com")), "a measured host is decided by verdicts, not by another probe");
	}

	@Test
	void hostBytesAreBookedAgainstTheHostAfterTheAttemptStartFlip() throws Exception {
		DownloadData data = new DownloadData(Path.of("mod.jar"), "a".repeat(40), "cdn.example.com", 5 * MiB);
		assertEquals("cdn.example.com", data.bookedDomain(), "a platform attempt books against the platform");
		data.route = DownloadData.Route.HOST;
		assertEquals(RescuePolicy.HOST_DOMAIN, data.bookedDomain(),
				"an attempt the burned-budget fallback moved to the host must book its bytes against the host, or the platform it stopped using reads as faster than it is");
	}

	@Test
	void aProbeFillsTheFarEndAndLeavesThePrefixAlone() throws Exception {
		setUp();
		DownloadData data = attempt("cdn.example.com");
		Path directory = PartialResume.directory(layout.stagingDirectory(), data.sha1);
		byte[] served = new byte[(int) NetUtils.WIRE_CHUNK_BYTES];
		AtomicLong requested = new AtomicLong();
		long tileStart = HostProbe.takeableTile(directory, data.fileSize);
		assertEquals(4 * MiB, tileStart, "the far end is where the platform has not reached, so neither side sends the same bytes");
		assertEquals(0, PartialResume.nextByte(directory, data.fileSize), "the platform is still at the front of the file");
		var settled = new ArrayBlockingQueue<HostProbe.Outcome>(1);
		probe.probe(new StubTransport(served, requested), directory, data, tileStart, settled::offer);
		assertEquals(HostProbe.Outcome.MEASURED, settled.poll(5, TimeUnit.SECONDS));
		assertEquals(4 * MiB, requested.get(), "the probe asks for one tile, never the whole file");

		assertEquals(0, PartialResume.nextByte(directory, data.fileSize),
				"the far end is not a prefix, so the platform still resumes from zero and never sees the probe's bytes");
		assertEquals(served.length, Files.size(PartialResume.sliceFile(directory, tileStart)), "a filled tile takes its real name");
		assertEquals(1, countTiles(directory, data.fileSize), "one tile is whole, the rest are untouched");

		assertEquals(0, HostProbe.takeableTile(directory, data.fileSize),
				"the far end is now whole, so the only thing left to take is the last tile the platform has not reached, and it is not in reach of the same probe");
	}

	@Test
	void aHostThatWillNotAnswerEndsProbingWithoutBurningAPlatformAttempt() throws Exception {
		setUp();
		DownloadData data = attempt("cdn.example.com");
		Path directory = PartialResume.directory(layout.stagingDirectory(), data.sha1);
		// The platform has already written something, so the staging directory is there to be gone or to stay.
		Files.createDirectories(directory);
		var settled = new ArrayBlockingQueue<HostProbe.Outcome>(1);
		probe.probe(new StubTransport(null, new AtomicLong()), directory, data, HostProbe.takeableTile(directory, data.fileSize), settled::offer);
		assertEquals(HostProbe.Outcome.UNREACHABLE, settled.poll(5, TimeUnit.SECONDS));
		assertEquals(data.fileSize, data.remainingBytes.get(), "a probe is not a download attempt, so the object's own progress never moves and the platform is not charged for host bytes");
	}

	@Test
	void aProbeThatOutranItsObjectIsNotAFaultOfTheHost() throws Exception {
		setUp();
		DownloadData data = attempt("cdn.example.com");
		Path directory = PartialResume.directory(layout.stagingDirectory(), data.sha1);
		Files.createDirectories(directory);
		// The object completed while the take was in flight, so the staging directory is gone by the time the take settles.
		Files.delete(directory);
		var settled = new ArrayBlockingQueue<HostProbe.Outcome>(1);
		probe.probe(new StubTransport(null, new AtomicLong()), directory, data, 4 * MiB, settled::offer);
		assertEquals(HostProbe.Outcome.OBJECT_FINISHED, settled.poll(5, TimeUnit.SECONDS),
				"a take the object finished under is not the host failing, so probing must continue");
	}

	private DownloadData attempt(String domain) {
		return new DownloadData(Path.of("mod.jar"), "a".repeat(40), domain, 8 * MiB);
	}

	private static long countTiles(Path directory, long fileSize) throws IOException {
		long tiles = 0;
		for (long start = 0; start < fileSize; start += NetUtils.WIRE_CHUNK_BYTES)
			if (Files.isRegularFile(PartialResume.sliceFile(directory, start))) tiles++;
		return tiles;
	}

	/** Answers a bounded take from memory and records how much it was asked for. */
	private static final class StubTransport implements HostTiles {

		private final byte[] served;
		private final AtomicLong requested;

		StubTransport(byte[] served, AtomicLong requested) {
			this.served = served;
			this.requested = requested;
		}

		@Override
		public CompletableFuture<Path> downloadTile(byte[] sha1Hex, Path sliceDirectory, long fileSize, long tileStart, IntConsumer progress) {
			if (served == null) return CompletableFuture.failedFuture(new IOException("no host wire in this test"));
			long length = PartialResume.sliceLength(tileStart, fileSize);
			requested.set(length);
			try {
				Files.createDirectories(sliceDirectory);
				try (OutputStream out = Files.newOutputStream(PartialResume.sliceHostTemp(sliceDirectory, tileStart))) {
					out.write(served, 0, (int) length);
				}
				PartialResume.promoteSlice(sliceDirectory, tileStart, fileSize);
				progress.accept((int) length);
				return CompletableFuture.completedFuture(sliceDirectory);
			} catch (IOException e) {
				return CompletableFuture.failedFuture(e);
			}
		}
	}
}
