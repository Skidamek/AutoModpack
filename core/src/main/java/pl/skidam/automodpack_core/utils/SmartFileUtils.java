package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class SmartFileUtils {

	public static final Path CWD = Path.of(System.getProperty("user.dir"));

	// --- File Operations (Delete / Copy / Move) ---

	public static void executeOrder66(Path file) {
		executeOrder66(file, true);
	}

	public static void executeOrder66(Path file, boolean saveDummyFiles) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
		}

		if (Files.isRegularFile(file)) {
			LegacyClientCacheUtils.dummyIT(file);
			if (saveDummyFiles) LegacyClientCacheUtils.saveDummyFiles();
		}
	}

	public static boolean isValidFile(Path file, long expectedSize, String expectedSha1) {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
		try {
			return Files.size(file) == expectedSize && expectedSha1.equalsIgnoreCase(HashUtils.getHash(file));
		} catch (IOException e) {
			return false;
		}
	}

	public static boolean copyVerifiedAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		if (isValidFile(targetFile, expectedSize, expectedSha1)) return false;
		if (!isValidFile(sourceFile, expectedSize, expectedSha1)) throw new IOException("Source file failed size/SHA-1 verification: " + sourceFile);

		createParentDirs(targetFile);
		Path parent = targetFile.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("Target path has no parent: " + targetFile);
		Path temporary = Files.createTempFile(parent, "." + targetFile.getFileName() + ".", ".tmp");
		try {
			Files.copy(sourceFile, temporary, StandardCopyOption.REPLACE_EXISTING);
			forceFile(temporary);
			if (!isValidFile(temporary, expectedSize, expectedSha1)) throw new IOException("Copied file failed size/SHA-1 verification: " + temporary);
			moveAtomicReplace(temporary, targetFile);
			return true;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public record CopyRequest(Path source, Path target, long expectedSize, String expectedSha1) {}

	public static class CopyBatchException extends IOException {
		private final Path target;

		public CopyBatchException(Path target, Throwable cause) {
			super("Failed to install " + target, cause);
			this.target = target;
		}

		public Path target() {
			return target;
		}
	}

	public static void copyVerifiedAtomicBatch(Collection<CopyRequest> requests, int maxConcurrency) throws IOException {
		if (requests.isEmpty()) return;
		if (maxConcurrency < 1) throw new IllegalArgumentException("Copy concurrency must be positive");
		List<CopyRequest> orderedRequests = List.copyOf(requests);
		ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxConcurrency, orderedRequests.size()));
		List<Callable<Void>> tasks = orderedRequests.stream().map(request -> (Callable<Void>) () -> {
			try {
				copyVerifiedAtomic(request.source(), request.target(), request.expectedSize(), request.expectedSha1());
				return null;
			} catch (IOException | RuntimeException failure) {
				throw new CopyBatchException(request.target(), failure);
			}
		}).toList();
		IOException failure = null;
		Error error = null;
		boolean interrupted = false;
		try {
			List<Future<Void>> futures = executor.invokeAll(tasks);
			for (int index = 0; index < futures.size(); index++) {
				try {
					futures.get(index).get();
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (cause instanceof Error taskError) {
						if (error == null) {
							error = taskError;
						} else {
							error.addSuppressed(taskError);
						}
					} else if (failure == null) {
						failure = cause instanceof CopyBatchException copyFailure
								? copyFailure
								: new CopyBatchException(orderedRequests.get(index).target(), cause);
					}
				} catch (CancellationException e) {
					if (failure == null) failure = new CopyBatchException(orderedRequests.get(index).target(), e);
				}
			}
		} catch (InterruptedException e) {
			interrupted = true;
			failure = new IOException("Interrupted while installing files", e);
		} finally {
			executor.shutdownNow();
			while (!executor.isTerminated()) {
				try {
					executor.awaitTermination(1, TimeUnit.DAYS);
				} catch (InterruptedException e) {
					interrupted = true;
					if (failure == null) failure = new IOException("Interrupted while waiting for file installation to stop", e);
				}
			}
			if (interrupted) Thread.currentThread().interrupt();
		}
		if (error != null) {
			if (failure != null) error.addSuppressed(failure);
			throw error;
		}
		if (failure != null) throw failure;
	}

	public static void promoteVerifiedAtomic(Path temporary, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		forceFile(temporary);
		if (!isValidFile(temporary, expectedSize, expectedSha1)) throw new IOException("Downloaded file failed size/SHA-1 verification: " + temporary);
		createParentDirs(targetFile);
		moveAtomicReplace(temporary, targetFile);
	}

	private static void moveAtomicReplace(Path sourceFile, Path targetFile) throws IOException {
		try {
			Files.move(sourceFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			throw new IOException("Atomic replacement is unsupported for " + targetFile, e);
		}
	}

	private static void forceFile(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
	}

	public static void copyFile(Path sourceFile, Path targetFile) throws IOException {
		createParentDirs(targetFile);

		// Use a temp file to ensure atomicity at the destination
		Path tempTargetFile = targetFile.resolveSibling(targetFile.getFileName() + ".tmp_" + System.nanoTime());

		try {
			// Copy Source -> Temp
			performSmartCopy(sourceFile, tempTargetFile);
			// Promote Temp -> Target
			moveFile(tempTargetFile, targetFile);
		} catch (Exception e) {
			try {
				Files.deleteIfExists(tempTargetFile);
			} catch (IOException ignored) {
			}
			LOGGER.error("Failed to copy file from {} to {}", sourceFile, targetFile, e);
			throw e;
		}
	}

	public static void moveFile(Path sourceFile, Path targetFile) throws IOException {
		try {
			// Atomic Move: The gold standard for consistency
			Files.move(sourceFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			try {
				// Fallback: Standard Move
				Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ex) {
				// Last Resort: Copy & Delete (Required for cross-drive moves)
				performSmartCopy(sourceFile, targetFile);
				Files.deleteIfExists(sourceFile);
			}
		}
	}

	private static void performSmartCopy(Path source, Path target) throws IOException {
		try {
			// Try Native reflink (CoW) on Java 20+
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			// Fallback to Zero-Copy Channel Transfer (Handles locked files on Windows)
			copyViaChannel(source, target);
		}
	}

	private static void copyViaChannel(Path sourceFile, Path targetFile) throws IOException {
		try (FileChannel source = LockFreeInputStream.openChannel(sourceFile);
				FileChannel target = FileChannel.open(targetFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

			long count = source.size();
			long position = 0;
			while (position < count) {
				position += source.transferTo(position, count - position, target);
			}
		}
	}

	// --- Directory & Path Logic ---

	public static void createParentDirs(Path file) throws IOException {
		Path parent = file.getParent();
		if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
	}

	public static void createParentDirsNoEx(Path file) {
		try {
			createParentDirs(file);
		} catch (IOException e) {
			LOGGER.error("Failed to create parent dirs", e);
		}
	}

	public static boolean isEmptyDirectory(Path parentPath) throws IOException {
		if (!Files.isDirectory(parentPath)) return false;
		try (Stream<Path> pathStream = Files.list(parentPath)) {
			return pathStream.findAny().isEmpty();
		}
	}

	public static boolean compareSmallFile(Path path, byte[] referenceBytes) {
		try {
			if (Files.size(path) != referenceBytes.length) return false;
			try (InputStream is = new LockFreeInputStream(path)) {
				return Arrays.equals(is.readNBytes(referenceBytes.length), referenceBytes);
			}
		} catch (Exception e) {
			LOGGER.error("Error comparing file: {}", path, e);
			return false;
		}
	}

	public static Path getPathFromCWD(String path) {
		return getPath(CWD, path);
	}

	public static Path getPath(Path origin, String path) {
		if (origin == null) throw new IllegalArgumentException("Origin path must not be null");
		if (path == null || path.isBlank()) return origin;

		if (path.indexOf('\\') >= 0) path = path.replace('\\', '/');
		if (path.startsWith("/")) path = path.substring(1);

		return origin.resolve(path).normalize();
	}

	public static String formatPath(final Path modpackFile, final Path modpackPath) {
		if (modpackPath == null || modpackFile == null) throw new IllegalArgumentException("Arguments cannot be null");

		String modpackFileStrAbs = modpackFile.toAbsolutePath().normalize().toString();
		String modpackPathStrAbs = modpackPath.toAbsolutePath().normalize().toString();
		String cwdStrAbs = CWD.toAbsolutePath().normalize().toString();

		String formattedFile = modpackFile.normalize().toString();

		if (modpackFileStrAbs.startsWith(modpackPathStrAbs)) {
			formattedFile = modpackFileStrAbs.substring(modpackPathStrAbs.length());
		} else if (modpackFileStrAbs.startsWith(cwdStrAbs)) { formattedFile = modpackFileStrAbs.substring(cwdStrAbs.length()); }

		formattedFile = formattedFile.replace(File.separator, "/");
		return formattedFile.startsWith("/") ? formattedFile : "/" + formattedFile;
	}
}
