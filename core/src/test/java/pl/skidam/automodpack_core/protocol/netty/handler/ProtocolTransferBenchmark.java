package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.protocol.NetUtils.DEFAULT_CHUNK_SIZE;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;

import pl.skidam.automodpack_core.protocol.ProtocolFrameCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;

/** Manual benchmark. Run with {@code ./gradlew :core:benchmarkProtocolTransfer}. */
public final class ProtocolTransferBenchmark {
	private static final int MEBIBYTE = 1024 * 1024;
	private static final int WARMUP_BYTES_PER_WORKER = 32 * MEBIBYTE;
	private static final int MEASURE_BYTES_PER_WORKER = 128 * MEBIBYTE;
	private static final int MEASURE_REPETITIONS = 3;
	private static final int RTT_CONTROL_ITEMS = 100;
	private static final int RTT_CONTROL_LANES = 5;
	private static final long RTT_CONTROL_NANOS = 40_000_000L;
	private static final ByteBufAllocator ALLOCATOR = PooledByteBufAllocator.DEFAULT;
	private static volatile int checksum;

	private ProtocolTransferBenchmark() {}

	public static void main(String[] args) throws Exception {
		byte[] payload = incompressiblePayload(DEFAULT_CHUNK_SIZE);
		System.out.printf("Protocol transfer benchmark: Java %s, processors=%d, chunk=%d MiB, codec=%s%n", System.getProperty("java.version"),
				Runtime.getRuntime().availableProcessors(), DEFAULT_CHUNK_SIZE / MEBIBYTE, CompressionType.ZSTD);
		System.out.println("Workload: deterministic incompressible bytes (representative of already-compressed JAR content), 32 MiB warmup and three 128 MiB measurements per worker.");

		int processors = Runtime.getRuntime().availableProcessors();
		for (int workers = 1; workers <= processors; workers++) printCompressionMeasurement(workers, payload);
		printFilePipelineMeasurement(payload);
		printRttControl();
		System.out.println("checksum=" + checksum);
	}

	private static void printCompressionMeasurement(int workers, byte[] payload) throws Exception {
		measureCompression(workers, payload, WARMUP_BYTES_PER_WORKER);
		Measurement[] measurements = new Measurement[MEASURE_REPETITIONS];
		for (int repetition = 0; repetition < measurements.length; repetition++) measurements[repetition] = measureCompression(workers, payload, MEASURE_BYTES_PER_WORKER);
		Arrays.sort(measurements, Comparator.comparingLong(Measurement::nanos));
		Measurement measurement = measurements[measurements.length / 2];
		double throughput = measurement.bytes() / (double) MEBIBYTE / (measurement.nanos() / 1_000_000_000.0);
		double allocatedPerGib = measurement.allocatedBytes() * 1024.0 / measurement.bytes();
		System.out.printf("compression workers=%d: %.1f MiB/s, %.1f MiB allocated/GiB input, wall=%.3f s%n", workers, throughput, allocatedPerGib,
				measurement.nanos() / 1_000_000_000.0);
	}

	private static Measurement measureCompression(int workers, byte[] payload, int bytesPerWorker) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		try {
			@SuppressWarnings("unchecked")
			Future<WorkerMeasurement>[] futures = new Future[workers];
			long started = System.nanoTime();
			for (int worker = 0; worker < workers; worker++) futures[worker] = executor.submit(new CompressionWorker(payload, bytesPerWorker));
			long allocated = 0;
			long bytes = 0;
			for (Future<WorkerMeasurement> future : futures) {
				WorkerMeasurement result = future.get();
				allocated += result.allocatedBytes();
				bytes += result.bytes();
			}
			return new Measurement(bytes, System.nanoTime() - started, allocated);
		} finally {
			executor.shutdownNow();
		}
	}

	private static void printFilePipelineMeasurement(byte[] payload) throws Exception {
		Path fixture = Files.createTempFile("automodpack-protocol-benchmark-", ".bin");
		try {
			writeFixture(fixture, payload, MEASURE_BYTES_PER_WORKER);
			readAndCompress(fixture, false);
			ChunkMeasurement measurement = readAndCompress(fixture, true);
			double throughput = measurement.bytes() / (double) MEBIBYTE / (measurement.totalNanos() / 1_000_000_000.0);
			long[] sorted = measurement.chunkNanos().clone();
			Arrays.sort(sorted);
			System.out.printf("cached file + compression on one thread: %.1f MiB/s, chunk service p50=%.3f ms p95=%.3f ms max=%.3f ms%n", throughput,
					percentile(sorted, 50) / 1_000_000.0, percentile(sorted, 95) / 1_000_000.0, sorted[sorted.length - 1] / 1_000_000.0);
		} finally {
			Files.deleteIfExists(fixture);
		}
	}

	private static ChunkMeasurement readAndCompress(Path fixture, boolean record) throws Exception {
		long fileSize = Files.size(fixture);
		long[] chunkNanos = new long[(int) ((fileSize + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE)];
		CompressionCodec codec = CompressionFactory.createCodec(CompressionType.ZSTD);
		ProtocolFrameCodec.FrameScratch scratch = new ProtocolFrameCodec.FrameScratch();
		long bytes = 0;
		int chunkIndex = 0;
		long started = System.nanoTime();
		FileChannel channel = FileChannel.open(fixture, StandardOpenOption.READ);
		HeapChunkedNioStream stream = new HeapChunkedNioStream(channel, DEFAULT_CHUNK_SIZE, fileSize);
		try {
			while (!stream.isEndOfInput()) {
				long chunkStarted = System.nanoTime();
				ByteBuf input = stream.readChunk(ALLOCATOR);
				if (input == null) continue;
				ByteBuf output = ALLOCATOR.ioBuffer(codec.maxCompressedLength(input.readableBytes()) + ProtocolFrameCodec.HEADER_BYTES);
				try {
					ProtocolFrameCodec.write(output, codec, input, DEFAULT_CHUNK_SIZE, scratch);
					checksum ^= output.getByte(output.writerIndex() - 1);
					bytes += input.readableBytes();
				} finally {
					output.release();
					input.release();
				}
				if (record) chunkNanos[chunkIndex++] = System.nanoTime() - chunkStarted;
			}
		} finally {
			stream.close();
		}
		return new ChunkMeasurement(bytes, System.nanoTime() - started, record ? Arrays.copyOf(chunkNanos, chunkIndex) : new long[0]);
	}

	private static void printRttControl() throws Exception {
		int fiveItemBatchCount = divideRoundUp(RTT_CONTROL_ITEMS, RTT_CONTROL_LANES);
		long v1Nanos = medianRequestTime(RTT_CONTROL_ITEMS, RTT_CONTROL_LANES);
		long fiveItemV2Nanos = medianRequestTime(fiveItemBatchCount, 1);
		long balancedV2Nanos = medianRequestTime(RTT_CONTROL_LANES, RTT_CONTROL_LANES);
		System.out.printf("%.0f ms RTT control, %d tiny files: V1=%d frames/%.1f ms, five-item V2=%d frames/%.1f ms, lane-balanced V2=%d frames/%.1f ms%n",
				RTT_CONTROL_NANOS / 1_000_000.0, RTT_CONTROL_ITEMS, RTT_CONTROL_ITEMS, v1Nanos / 1_000_000.0, fiveItemBatchCount,
				fiveItemV2Nanos / 1_000_000.0, RTT_CONTROL_LANES, balancedV2Nanos / 1_000_000.0);
	}

	private static long medianRequestTime(int requestCount, int parallelism) throws Exception {
		long[] measurements = new long[MEASURE_REPETITIONS];
		for (int repetition = 0; repetition < measurements.length; repetition++) measurements[repetition] = runRequestControl(requestCount, parallelism);
		Arrays.sort(measurements);
		return measurements[measurements.length / 2];
	}

	private static long runRequestControl(int requestCount, int parallelism) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(parallelism);
		try {
			@SuppressWarnings("unchecked")
			Future<Void>[] futures = new Future[requestCount];
			long started = System.nanoTime();
			for (int request = 0; request < requestCount; request++) futures[request] = executor.submit(() -> {
				long deadline = System.nanoTime() + RTT_CONTROL_NANOS;
				for (long remaining; (remaining = deadline - System.nanoTime()) > 0;) LockSupport.parkNanos(remaining);
				return null;
			});
			for (Future<Void> future : futures) future.get();
			return System.nanoTime() - started;
		} finally {
			executor.shutdownNow();
		}
	}

	private static int divideRoundUp(int dividend, int divisor) {
		return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
	}

	private static void writeFixture(Path fixture, byte[] payload, int bytes) throws IOException {
		try (FileChannel channel = FileChannel.open(fixture, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			for (int written = 0; written < bytes;) {
				ByteBuffer buffer = ByteBuffer.wrap(payload, 0, Math.min(payload.length, bytes - written));
				while (buffer.hasRemaining()) written += channel.write(buffer);
			}
		}
	}

	private static byte[] incompressiblePayload(int length) {
		byte[] payload = new byte[length];
		new SplittableRandom(0x4155544f4d4f4450L).nextBytes(payload);
		return payload;
	}

	private static long percentile(long[] sorted, int percentile) {
		return sorted[Math.min(sorted.length - 1, (sorted.length * percentile + 99) / 100 - 1)];
	}

	private record Measurement(long bytes, long nanos, long allocatedBytes) {}

	private record WorkerMeasurement(long bytes, long allocatedBytes) {}

	private record ChunkMeasurement(long bytes, long totalNanos, long[] chunkNanos) {}

	private static final class CompressionWorker implements Callable<WorkerMeasurement> {
		private final byte[] payload;
		private final int bytes;

		private CompressionWorker(byte[] payload, int bytes) {
			this.payload = payload;
			this.bytes = bytes;
		}

		@Override
		public WorkerMeasurement call() throws Exception {
			CompressionCodec codec = CompressionFactory.createCodec(CompressionType.ZSTD);
			ProtocolFrameCodec.FrameScratch scratch = new ProtocolFrameCodec.FrameScratch();
			com.sun.management.ThreadMXBean threadBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			if (threadBean.isThreadAllocatedMemorySupported() && !threadBean.isThreadAllocatedMemoryEnabled()) threadBean.setThreadAllocatedMemoryEnabled(true);
			long threadId = Thread.currentThread().getId();
			long allocatedBefore = threadBean.isThreadAllocatedMemoryEnabled() ? threadBean.getThreadAllocatedBytes(threadId) : 0;
			for (int processed = 0; processed < bytes; processed += payload.length) {
				int length = Math.min(payload.length, bytes - processed);
				ByteBuf input = ALLOCATOR.heapBuffer(length, length).writeBytes(payload, 0, length);
				ByteBuf output = ALLOCATOR.ioBuffer(codec.maxCompressedLength(length) + ProtocolFrameCodec.HEADER_BYTES);
				try {
					ProtocolFrameCodec.write(output, codec, input, DEFAULT_CHUNK_SIZE, scratch);
					checksum ^= output.getByte(output.writerIndex() - 1);
				} finally {
					output.release();
					input.release();
				}
			}
			long allocatedAfter = threadBean.isThreadAllocatedMemoryEnabled() ? threadBean.getThreadAllocatedBytes(threadId) : 0;
			return new WorkerMeasurement(bytes, Math.max(0, allocatedAfter - allocatedBefore));
		}
	}
}
