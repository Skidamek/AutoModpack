package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class HashUtils {
	private static final String SHA_1 = "SHA-1";
	private static final Pattern SHA1_PATTERN = Pattern.compile("[0-9a-fA-F]{40}");

	private HashUtils() {}

	/** Returns a SHA-1 digest encoded as lowercase hexadecimal. */
	public static String sha1(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance(SHA_1).digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 is unavailable", e);
		}
	}

	/** Returns the SHA-1 digest of the UTF-8 representation of {@code value}. */
	public static String sha1(String value) {
		return sha1(value.getBytes(StandardCharsets.UTF_8));
	}

	/** Creates a SHA-1 digest for callers that need to update it incrementally. */
	public static MessageDigest newSha1Digest() {
		try {
			return MessageDigest.getInstance(SHA_1);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 is unavailable", e);
		}
	}

	/** Returns whether {@code value} is a 40-character hexadecimal SHA-1 digest. */
	public static boolean isSha1(String value) {
		return value != null && SHA1_PATTERN.matcher(value).matches();
	}

	/** Returns whether {@code value} is a lowercase 40-character hexadecimal SHA-1 digest. */
	public static boolean isCanonicalSha1(String value) {
		return isSha1(value) && value.equals(value.toLowerCase(Locale.ROOT));
	}

	/** The {@link #getHash} of every {@code .jar} file directly in {@code dir}; empty if it isn't a directory. */
	public static Set<String> getJarHashes(Path dir) {
		Set<String> hashes = new HashSet<>();
		if (dir == null || !Files.isDirectory(dir)) return hashes;
		try (Stream<Path> stream = Files.list(dir)) {
			stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".jar")).forEach(jar -> {
				String hash = getHash(jar);
				if (hash != null) hashes.add(hash);
			});
		} catch (Exception e) {
			LOGGER.debug("Failed to list directory for jar hashes: {}", dir, e);
		}
		return hashes;
	}

	public static String getHash(Path path) {
		try {
			MessageDigest digest = newSha1Digest();
			try (InputStream is = Files.newInputStream(path)) {
				byte[] buffer = new byte[64 * 1024];
				int bytesRead;
				while ((bytesRead = is.read(buffer)) != -1) {
					digest.update(buffer, 0, bytesRead);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException ignored) {
			// File might not exist
		} catch (Exception e) {
			LOGGER.error("Failed to get hash for path: {}", path, e);
		}
		return null;
	}

	/**
	 * Calculates the CurseForge specific MurmurHash2.
	 * Normalized by ignoring whitespace (0x9, 0xA, 0xD, 0x20).
	 */
	public static String getCurseforgeMurmurHash(Path file) throws IOException {
		if (!Files.exists(file)) return null;

		// MurmurHash2 Constants
		final int m = 0x5bd1e995;
		final int r = 24;
		final int seed = 1;

		// Pass 1: Count valid non-whitespace bytes to determine the hash seed
		long validLength = 0;
		byte[] buffer = new byte[64 * 1024];

		try (InputStream is = Files.newInputStream(file)) {
			int bytesRead;
			while ((bytesRead = is.read(buffer)) != -1) {
				for (int i = 0; i < bytesRead; i++) {
					if (!isWhitespace(buffer[i])) validLength++;
				}
			}
		}

		// Pass 2: Calculate Hash
		long h = (seed ^ validLength);
		long k = 0;
		int shift = 0;

		try (InputStream is = Files.newInputStream(file)) {
			int bytesRead;
			while ((bytesRead = is.read(buffer)) != -1) {
				for (int i = 0; i < bytesRead; i++) {
					byte b = buffer[i];
					if (isWhitespace(b)) continue;

					// Append byte to current 4-byte chunk
					k = k | ((long) (b & 0xFF) << shift);
					shift += 8;

					if (shift == 32) {
						k = (k * m) & 0xFFFFFFFFL;
						k ^= (k >>> r);
						k = (k * m) & 0xFFFFFFFFL;

						h = (h * m) & 0xFFFFFFFFL;
						h ^= k;

						// Reset chunk
						k = 0;
						shift = 0;
					}
				}
			}
		}

		// Handle tail
		if (shift > 0) {
			h ^= k;
			h = (h * m) & 0xFFFFFFFFL;
		}

		h ^= (h >>> 13);
		h = (h * m) & 0xFFFFFFFFL;
		h ^= (h >>> 15);

		return String.valueOf(h);
	}

	private static boolean isWhitespace(byte b) {
		return b == 0x9 || b == 0xA || b == 0xD || b == 0x20;
	}
}
