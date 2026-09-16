package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Low-level digest primitives; path-keyed callers add caching through {@code FileCache}. */
public final class HashUtils {
	public static final int SHA1_HEX_LENGTH = 40;
	private static final String SHA_1 = "SHA-1";
	private static final int STREAM_BUFFER = 32 * 1024;
	private static final int MURMUR_M = 0x5bd1e995;
	private static final int MURMUR_R = 24;
	private static final int MURMUR_SEED = 1;
	private static final long BYTE_ONES = 0x0101010101010101L;
	private static final long BYTE_HIGHS = 0x8080808080808080L;
	private static final VarHandle LITTLE_ENDIAN_LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

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
		if (value == null || value.length() != SHA1_HEX_LENGTH) return false;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f') && !(character >= 'A' && character <= 'F')) return false;
		}
		return true;
	}

	/** Returns whether {@code value} is a lowercase 40-character hexadecimal SHA-1 digest. */
	public static boolean isCanonicalSha1(String value) {
		return isSha1(value) && value.equals(value.toLowerCase(Locale.ROOT));
	}

	/** Validates and returns the lowercase canonical representation of a SHA-1 digest. */
	public static String normalizeSha1(String value) {
		if (!isSha1(value)) throw new IllegalArgumentException("Invalid SHA-1 digest");
		return value.toLowerCase(Locale.ROOT);
	}

	/** Validates and returns the lowercase canonical representation of a required SHA-1 digest, described by {@code description} in the failure message. */
	public static String requireDigest(String value, String description) {
		if (!isSha1(value)) throw new IllegalArgumentException("Invalid " + description);
		return normalizeSha1(value);
	}

	/** Full SHA-1 of current bytes. Path-keyed identity goes through {@code FileIntegrity} / {@code FileCache}. */
	public static String getHash(Path path) {
		try {
			MessageDigest digest = newSha1Digest();
			try (InputStream is = Files.newInputStream(path)) {
				digestStream(digest, is);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException ignored) {
			// File might not exist
		} catch (Exception e) {
			LOGGER.error("Failed to get hash for path: {}", path, e);
		}
		return null;
	}

	/** Copies {@code source} to {@code destination} and returns the SHA-1 of the bytes written. */
	public static String copyAndSha1(Path source, Path destination) throws IOException {
		MessageDigest digest = newSha1Digest();
		try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(destination)) {
			byte[] buffer = new byte[STREAM_BUFFER];
			int bytesRead;
			while ((bytesRead = in.read(buffer)) != -1) {
				digest.update(buffer, 0, bytesRead);
				out.write(buffer, 0, bytesRead);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void digestStream(MessageDigest digest, InputStream is) throws IOException {
		byte[] buffer = new byte[STREAM_BUFFER];
		int bytesRead;
		while ((bytesRead = is.read(buffer)) != -1) digest.update(buffer, 0, bytesRead);
	}

	/**
	 * Calculates the CurseForge specific MurmurHash2.
	 * Normalized by ignoring whitespace (0x9, 0xA, 0xD, 0x20).
	 */
	public static String getCurseforgeMurmurHash(Path file) throws IOException {
		if (!Files.exists(file)) return null;
		byte[] array = new byte[STREAM_BUFFER];
		ByteBuffer buffer = ByteBuffer.wrap(array);
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			long validLength = 0;
			int bytesRead;
			while ((bytesRead = channel.read(buffer)) != -1) {
				validLength += countNonWhitespace(array, bytesRead);
				buffer.clear();
			}
			channel.position(0);
			long h = MURMUR_SEED ^ validLength;
			long k = 0;
			int shift = 0;
			while ((bytesRead = channel.read(buffer)) != -1) {
				int i = 0;
				while (i < bytesRead) {
					if (shift == 0 && i + 8 <= bytesRead) {
						long word = (long) LITTLE_ENDIAN_LONGS.get(array, i);
						if (!containsCurseForgeWhitespace(word)) {
							h = murmurMix(h, word & 0xFFFFFFFFL);
							h = murmurMix(h, word >>> 32);
							i += 8;
							continue;
						}
					}
					byte b = array[i++];
					if (isWhitespace(b)) continue;
					k |= (long) (b & 0xFF) << shift;
					shift += 8;
					if (shift == 32) {
						h = murmurMix(h, k);
						k = 0;
						shift = 0;
					}
				}
				buffer.clear();
			}
			if (shift > 0) {
				h ^= k;
				h = (h * MURMUR_M) & 0xFFFFFFFFL;
			}
			h ^= h >>> 13;
			h = (h * MURMUR_M) & 0xFFFFFFFFL;
			h ^= h >>> 15;
			return String.valueOf(h);
		}
	}

	private static long countNonWhitespace(byte[] array, int length) {
		long validLength = 0;
		int i = 0;
		while (i + 8 <= length) {
			long word = (long) LITTLE_ENDIAN_LONGS.get(array, i);
			if (whitespaceHighBits(word) == 0) {
				validLength += 8;
				i += 8;
				continue;
			}
			int end = i + 8;
			while (i < end) {
				if (!isWhitespace(array[i])) validLength++;
				i++;
			}
		}
		while (i < length) {
			if (!isWhitespace(array[i])) validLength++;
			i++;
		}
		return validLength;
	}

	private static long murmurMix(long h, long k) {
		k = (k * MURMUR_M) & 0xFFFFFFFFL;
		k ^= k >>> MURMUR_R;
		k = (k * MURMUR_M) & 0xFFFFFFFFL;
		h = (h * MURMUR_M) & 0xFFFFFFFFL;
		return h ^ k;
	}

	private static boolean containsCurseForgeWhitespace(long word) {
		return whitespaceHighBits(word) != 0;
	}

	private static long whitespaceHighBits(long word) {
		return repeatedByteHighBits(word, 0x09) | repeatedByteHighBits(word, 0x0A) | repeatedByteHighBits(word, 0x0D) | repeatedByteHighBits(word, 0x20);
	}

	private static long repeatedByteHighBits(long word, int value) {
		long n = word ^ (BYTE_ONES * (value & 0xFF));
		return (n - BYTE_ONES) & ~n & BYTE_HIGHS;
	}

	private static boolean isWhitespace(byte b) {
		return b == 0x9 || b == 0xA || b == 0xD || b == 0x20;
	}
}
