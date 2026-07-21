package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;

public final class LegacyDummyFiles {
	public static final String SHA1 = "9338266f0549193d949c34a38554f914b3bf171d";
	public static final int SIZE = 189;

	private static final byte[] SIGNATURE = {80, 75, 3, 4, 20, 0, 8, 8, 8, 0, 89, 116, -44, 86, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20, 0, 4, 0, 77, 69, 84,
			65, 45, 73, 78, 70, 47, 77, 65, 78, 73, 70, 69, 83, 84, 46, 77, 70, -2, -54, 0, 0, -13, 77, -52, -53, 76, 75, 45, 46, -47, 13, 75, 45, 42, -50, -52,
			-49, -77, 82, 48, -44, 51, -32, -27, -30, -27, 2, 0, 80, 75, 7, 8, -78, 127, 2, -18, 27, 0, 0, 0, 25, 0, 0, 0, 80, 75, 1, 2, 20, 0, 20, 0, 8, 8, 8,
			0, 89, 116, -44, 86, -78, 127, 2, -18, 27, 0, 0, 0, 25, 0, 0, 0, 20, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 77, 69, 84, 65, 45, 73, 78,
			70, 47, 77, 65, 78, 73, 70, 69, 83, 84, 46, 77, 70, -2, -54, 0, 0, 80, 75, 5, 6, 0, 0, 0, 0, 1, 0, 1, 0, 70, 0, 0, 0, 97, 0, 0, 0, 0, 0};

	private LegacyDummyFiles() {}

	public static boolean matches(Path file) {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
		try {
			if (Files.size(file) != SIGNATURE.length) return false;
			ByteBuffer content = ByteBuffer.allocate(SIGNATURE.length);
			try (SeekableByteChannel channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
				while (content.hasRemaining() && channel.read(content) != -1) {
				}
				if (channel.read(ByteBuffer.allocate(1)) != -1) return false;
			}
			return !content.hasRemaining() && Arrays.equals(content.array(), SIGNATURE);
		} catch (IOException | UnsupportedOperationException e) {
			return false;
		}
	}

	static byte[] signatureForTesting() {
		return SIGNATURE.clone();
	}
}
