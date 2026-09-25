package pl.skidam.automodpack_core.modpack.generation;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

import pl.skidam.automodpack_core.utils.HashUtils;

/** Immutable object paths published for the active generation host. */
public final class GenerationHosting {

	/** Reserved hosting keys served beside the content-addressed object hashes. */
	public static final String HEAD_DOCUMENT_KEY = "head";
	public static final String JOURNAL_KEY = "journal";

	private final NavigableMap<String, Path> paths;

	/**
	 * Builds the hosting surface of one generation: the reserved head and journal keys, the policy document object,
	 * and one key per content file. The waiting track is an object like any other, and the head is the single source
	 * of its hash; a track a collect has already removed stops being hosted, exactly as an absent advertisement would.
	 */
	public static GenerationHosting of(Path headDocument, Path journal, String policySha1, ContentTree tree, String waitingMusicSha1, Function<String, Path> objectFile) {
		TreeMap<String, Path> paths = new TreeMap<>();
		paths.put(HEAD_DOCUMENT_KEY, headDocument);
		paths.put(JOURNAL_KEY, journal);
		paths.put(policySha1, objectFile.apply(policySha1));
		for (var file : tree.files().values()) paths.put(file.sha1(), objectFile.apply(file.sha1()));
		if (HashUtils.isSha1(waitingMusicSha1)) {
			String sha1 = HashUtils.normalizeSha1(waitingMusicSha1);
			Path object = objectFile.apply(sha1);
			if (Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) paths.put(sha1, object);
		}
		return new GenerationHosting(paths);
	}

	public GenerationHosting(Map<String, Path> paths) {
		TreeMap<String, Path> normalized = new TreeMap<>();
		if (paths != null) {
			for (var entry : paths.entrySet()) {
				String key = Objects.requireNonNull(entry.getKey(), "hosting path key");
				Path path = Objects.requireNonNull(entry.getValue(), "hosting path").toAbsolutePath().normalize();
				normalized.put(key, path);
			}
		}
		this.paths = Collections.unmodifiableNavigableMap(normalized);
	}

	public Path get(String key) {
		return paths.get(key);
	}

	public boolean containsKey(String key) {
		return paths.containsKey(key);
	}

	public NavigableMap<String, Path> asMap() {
		return paths;
	}
}
