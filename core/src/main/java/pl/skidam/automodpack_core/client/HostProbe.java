package pl.skidam.automodpack_core.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import pl.skidam.automodpack_core.protocol.HostTiles;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.utils.DownloadScheduler;

/**
 * Measures the host wire by filling one tile at the far end of an object the platform is already sending.
 *
 * <p>
 * The rescue compares the host against the platform sources by a ratio, so both sides need a measurement. The platform
 * sources measure themselves because downloads run through them. The host would only ever measure an object that already
 * routed to it, and no object does while the host is unmeasured, so its first fact never arrives. This takes one tile from
 * the far end instead, where the platform has not reached yet, so neither side sends bytes the other is already sending.
 *
 * <p>
 * The take is never cancelled. If the object finishes while a take is still running, the take settles as done and is not
 * charged to the host, because the host answered and the run simply moved on. A take that fails with the staging directory
 * gone is the same case. Only a take that fails while the directory is still there means the host would not answer.
 *
 * <p>
 * A tile rather than a sample window, because a bounded take measures TCP slow start instead of the link underneath it.
 * Slow start sends about 14.6 KB in the first round trip and doubles per round trip, so a small take reads roughly the same
 * on a 1 Gbps link as on a 10 MB/s one. A tile is about eight round trips. That biases the first estimate low, which is the
 * safe direction: an under-read leaves the rescue off, and an over-read would move traffic onto a host that cannot carry it.
 *
 * <p>
 * Measurement only. This runs while the host has no fact. Once it has one the policy decides from facts, and no tile is ever
 * taken to fill space speculatively.
 *
 * <h2>When a measurement happens</h2>
 *
 * The whole trigger table, so it is read here rather than reconstructed from three files:
 *
 * <ul>
 * <li>A speed sample is per domain and expires 60 seconds after it was taken. A domain with no live sample is unmeasured, and
 * a speed bar decided from an unmeasured domain is a guess, so the run stays where it is.
 * <li>The host is measured only while it has no live sample of its own. Once it does, {@link RescuePolicy} decides from that
 * sample and {@link #worthMeasuring(DownloadData)} says no.
 * <li>A platform source is measured before anything is decided about it, so a source that might turn out to be the fast one
 * never loses traffic on the strength of another source being slow.
 * <li>A probe therefore runs only when every candidate platform is measured and at least one is under the bar in
 * {@link RescuePolicy}. For most runs no platform is ever under it, so no probe runs and the host is never measured.
 * <li>Because samples expire, the probe re-runs every 60 seconds for as long as none of the above changes and the run has not
 * switched. Each run takes one more tile, which is why it takes the far end and not the front.
 * <li>A host that would not answer while its object was still there is not measured and is not asked again for that run. A take
 * its object finished under is neither, and probing continues.
 * </ul>
 */
final class HostProbe {

	/** What one probe established. */
	enum Outcome {
		/** The host measured and the scheduler now carries its estimate. */
		MEASURED,
		/** The take came back under the evidence floor, so its bytes pool and a later take completes them. */
		TOO_LITTLE,
		/** The object finished while the take was running, so there was nothing left to measure into. */
		OBJECT_FINISHED,
		/** The host would not answer, so it is not measured and not tried again this run. */
		UNREACHABLE
	}

	private final DownloadScheduler scheduler;
	private final RescuePolicy rescue;

	HostProbe(DownloadScheduler scheduler, RescuePolicy rescue) {
		this.scheduler = scheduler;
		this.rescue = rescue;
	}

	/** Whether this file's platform sources could change the routing: {@link RescuePolicy#worthProbing(java.util.List)} is the table. */
	boolean worthMeasuring(DownloadData data) {
		return rescue.worthProbing(data.platformDomains);
	}

	/**
	 * The last tile of this object that the platform has not reached and nobody has filled, or -1 when there is none. The
	 * file's own size and the whole tiles already named are all this reads, so it never has to open the filling tile the
	 * platform is writing and never sees a half tile as progress.
	 */
	static long takeableTile(Path sliceDirectory, long fileSize) throws IOException {
		if (fileSize <= NetUtils.WIRE_CHUNK_BYTES) return -1;
		long frontier = PartialResume.nextByte(sliceDirectory, fileSize);
		for (long start = (fileSize - 1) / NetUtils.WIRE_CHUNK_BYTES * (long) NetUtils.WIRE_CHUNK_BYTES; start >= frontier; start -= NetUtils.WIRE_CHUNK_BYTES) {
			if (PartialResume.presentLength(PartialResume.sliceFile(sliceDirectory, start), PartialResume.sliceLength(start, fileSize)) > 0) continue;
			return start;
		}
		return -1;
	}

	/** Takes one far tile and reports its rate as the host's speed. Nothing here cancels, requeues or charges. */
	void probe(HostTiles tiles, Path sliceDirectory, DownloadData data, long tileStart, Consumer<Outcome> settled) {
		AtomicLong bytes = new AtomicLong();
		AtomicLong firstByteNanos = new AtomicLong();
		tiles.downloadTile(data.sha1.getBytes(StandardCharsets.UTF_8), sliceDirectory, data.fileSize, tileStart, chunk -> {
			// Anchored at the first byte like every other sample: reaching the bytes is the transport's cost, not their rate.
			firstByteNanos.compareAndSet(0, System.nanoTime());
			bytes.addAndGet(chunk);
		}).whenComplete((result, error) -> {
			long received = bytes.get();
			long elapsed = System.nanoTime() - firstByteNanos.get();
			if (error != null || received <= 0 || elapsed <= 0) {
				settled.accept(Files.isDirectory(sliceDirectory) ? Outcome.UNREACHABLE : Outcome.OBJECT_FINISHED);
				return;
			}
			settled.accept(scheduler.report(RescuePolicy.HOST_DOMAIN, received, elapsed) ? Outcome.MEASURED : Outcome.TOO_LITTLE);
		});
	}

	static List<DownloadData> platformAttempts(Iterable<DownloadData> inFlight) {
		List<DownloadData> attempts = new ArrayList<>();
		for (DownloadData data : inFlight)
			if (data.route == DownloadData.Route.PLATFORM) attempts.add(data);
		return attempts;
	}
}
