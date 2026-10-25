package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * How names are told apart: {@link #key} joins every pair of names some case- or normalization-insensitive filesystem may
 * treat as one, so it can only say which names might be one file; {@link #sameFile} says which are.
 */
public final class PathFold {
	private PathFold() {}

	/**
	 * NFD then Java's full lower/upper/lower case mapping: over every BMP code point this joins exactly what macOS's
	 * case-insensitive fold joins (xnu bsd/vfs/vfs_unicode.c, which expands ß to ss and ﬁ to fi), plus I with dotless ı,
	 * which NTFS's upcase table joins; NTFS's other 1:1 upcase pairs fall inside it.
	 */
	public static String key(String path) {
		return Normalizer.normalize(path, Normalizer.Form.NFD).toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
	}

	/**
	 * Whether two spellings under {@code root} name one file: the filesystem resolves both to it, and they are not two
	 * entries of their own, as two hard links to one object are. A missing spelling is no file at all.
	 */
	public static boolean sameFile(Path root, String first, String second) throws IOException {
		try {
			if (!Files.isSameFile(root.resolve(first), root.resolve(second))) return false;
		} catch (NoSuchFileException e) {
			return false;
		}
		return first.equals(second) || !listed(root, first) || !listed(root, second);
	}

	/** Whether every name along the spelling is a directory entry exactly as spelled, not just a name the filesystem resolves. */
	private static boolean listed(Path root, String relative) throws IOException {
		Path current = root;
		for (Path name : root.relativize(root.resolve(relative))) {
			String spelled = name.toString();
			try (Stream<Path> entries = Files.list(current)) {
				if (entries.noneMatch(entry -> entry.getFileName().toString().equals(spelled))) return false;
			}
			current = current.resolve(spelled);
		}
		return true;
	}
}
