package pl.skidam.automodpack_core.modpack.generation;

import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The served content of one generation: every logical path mapped to its immutable bytes. */
public record ContentTree(NavigableMap<String, ContentFile> files) {
	private static final String TOKEN_DOMAIN = "automodpack-content-v1";

	public ContentTree {
		files = files == null ? new TreeMap<>() : new TreeMap<>(files);
	}

	public record ContentFile(String sha1, long size) {
		public ContentFile {
			if (!HashUtils.isCanonicalSha1(sha1)) throw new IllegalArgumentException("Invalid content SHA-1: " + sha1);
			if (size < 0) throw new IllegalArgumentException("Negative content size for " + sha1);
		}
	}

	/** The content token: the canonical identity of exactly this served file set. */
	public String token() {
		CanonicalEncoder encoder = new CanonicalEncoder().string(TOKEN_DOMAIN).integer(files.size());
		for (var entry : files.entrySet()) {
			encoder.string(entry.getKey()).string(entry.getValue().sha1()).longValue(entry.getValue().size());
		}
		return HashUtils.sha1(encoder.bytes());
	}

	public static ContentTree fromManifest(GroupManifest manifest) {
		Objects.requireNonNull(manifest, "manifest");
		NavigableMap<String, ContentFile> files = new TreeMap<>();
		for (var group : manifest.groups().entrySet()) {
			for (var file : group.getValue().files().entrySet()) {
				ContentFile content = new ContentFile(file.getValue().sha1(), file.getValue().size());
				ContentFile previous = files.putIfAbsent(file.getKey(), content);
				if (previous != null && !previous.equals(content))
					throw new IllegalArgumentException("Conflicting content for path '" + file.getKey() + "' in group '" + group.getKey() + "'");
			}
		}
		return new ContentTree(files);
	}

	public static ContentTree empty() {
		return new ContentTree(new TreeMap<>());
	}

	/** The content token of the served file set described by a policy document. */
	public static String tokenOf(GroupManifest manifest) {
		return fromManifest(manifest).token();
	}
}
