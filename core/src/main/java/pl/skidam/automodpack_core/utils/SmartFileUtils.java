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

	/** Copies a verified file without replacing a destination created by another operation. */
	public static boolean copyVerifiedCreateOnly(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		if (isValidFile(targetFile, expectedSize, expectedSha1)) return false;
		if (Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Target file already exists with different bytes: " + targetFile);
		if (!isValidFile(sourceFile, expectedSize, expectedSha1)) throw new IOException("Source file failed size/SHA-1 verification: " + sourceFile);

		createParentDirs(targetFile);
		Path parent = targetFile.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("Target path has no parent: " + targetFile);
		Path temporary = Files.createTempFile(parent, "." + targetFile.getFileName() + ".", ".tmp");
		try {
			Files.copy(sourceFile, temporary, StandardCopyOption.REPLACE_EXISTING);
			forceFile(temporary);
			if (!isValidFile(temporary, expectedSize, expectedSha1)) throw new IOException("Copied file failed size/SHA-1 verification: " + temporary);
			try {
				moveCreateOnly(temporary, targetFile);
			} catch (FileAlreadyExistsException raced) {
				if (!isValidFile(targetFile, expectedSize, expectedSha1)) throw new IOException("Target file was created with different bytes: " + targetFile, raced);
				return false;
			}
			if (!isValidFile(targetFile, expectedSize, expectedSha1)) throw new IOException("Created file failed size/SHA-1 verification: " + targetFile);
			return true;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/** Installs an immutable CAS object as a hard link, with verified-copy fallback for filesystems that cannot link it. */
	public static boolean linkVerifiedAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		if (isValidFile(targetFile, expectedSize, expectedSha1)) return false;
		if (!isValidFile(sourceFile, expectedSize, expectedSha1)) throw new IOException("Source file failed size/SHA-1 verification: " + sourceFile);
		createParentDirs(targetFile);
		Path parent = targetFile.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("Target path has no parent: " + targetFile);
		Path temporary = Files.createTempFile(parent, "." + targetFile.getFileName() + ".", ".tmp");
		Files.deleteIfExists(temporary);
		try {
			try {
				Files.createLink(temporary, sourceFile);
			} catch (UnsupportedOperationException | FileSystemException unsupportedLink) {
				Files.createFile(temporary);
				Files.copy(sourceFile, temporary, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!isValidFile(temporary, expectedSize, expectedSha1)) throw new IOException("Linked file failed size/SHA-1 verification: " + temporary);
			moveAtomicReplace(temporary, targetFile);
			return true;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public static void moveDirectoryAtomic(Path sourceDirectory, Path targetDirectory) throws IOException {
		if (sourceDirectory.toAbsolutePath().normalize().getParent() == null || targetDirectory.toAbsolutePath().normalize().getParent() == null)
			throw new IOException("Directory move requires concrete parent directories");
		try {
			Files.move(sourceDirectory, targetDirectory, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			throw new IOException("Atomic directory replacement is unsupported for " + targetDirectory, e);
		}
	}

	public static void deleteTree(Path directory) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isSymbolicLink(directory)) {
			Files.delete(directory);
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
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
		try {
			moveAtomicReplace(temporary, targetFile);
		} catch (AtomicMoveNotSupportedException crossFileSystem) {
			promoteAcrossFileSystems(temporary, targetFile, expectedSize, expectedSha1, crossFileSystem);
		}
	}

	private static void promoteAcrossFileSystems(Path temporary, Path targetFile, long expectedSize, String expectedSha1, AtomicMoveNotSupportedException crossFileSystem)
			throws IOException {
		Path targetParent = targetFile.toAbsolutePath().normalize().getParent();
		if (targetParent == null) throw new IOException("Target path has no parent: " + targetFile, crossFileSystem);
		Path targetTemporary = Files.createTempFile(targetParent, "." + targetFile.getFileName() + ".", ".tmp");
		try {
			Files.copy(temporary, targetTemporary, StandardCopyOption.REPLACE_EXISTING);
			forceFile(targetTemporary);
			if (!isValidFile(targetTemporary, expectedSize, expectedSha1))
				throw new IOException("Cross-filesystem promotion failed size/SHA-1 verification: " + targetTemporary, crossFileSystem);
			moveAtomicReplace(targetTemporary, targetFile);
		} finally {
			Files.deleteIfExists(targetTemporary);
		}
	}

	private static void moveAtomicReplace(Path sourceFile, Path targetFile) throws IOException {
		Files.move(sourceFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void moveCreateOnly(Path sourceFile, Path targetFile) throws IOException {
		try {
			Files.move(sourceFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(sourceFile, targetFile);
		}
	}

	private static void forceFile(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.force(true);
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
