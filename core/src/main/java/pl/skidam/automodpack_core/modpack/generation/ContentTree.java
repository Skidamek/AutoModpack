package pl.skidam.automodpack_core.modpack.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The served content of one generation: every unique (path, bytes) triple. Path is unique within a group, not across exclusive groups. */
public record ContentTree(NavigableMap<String, NavigableSet<ContentFile>> files) {
	private static final String TOKEN_DOMAIN = "automodpack-content-v1";
	private static final NavigableSet<ContentFile> NO_FILES = Collections.unmodifiableNavigableSet(new TreeSet<>());
	public ContentTree {
		TreeMap<String, NavigableSet<ContentFile>> copy = new TreeMap<>();
		if (files != null) for (var entry : files.entrySet()) {
			if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
			copy.put(entry.getKey(), Collections.unmodifiableNavigableSet(new TreeSet<>(entry.getValue())));
		}
		files = Collections.unmodifiableNavigableMap(copy);
	}

	public record ContentFile(String sha1, long size) implements Comparable<ContentFile> {
		public ContentFile {
			if (!HashUtils.isCanonicalSha1(sha1)) throw new IllegalArgumentException("Invalid content SHA-1: " + sha1);
			if (size < 0) throw new IllegalArgumentException("Negative content size for " + sha1);
		}

		@Override
		public int compareTo(ContentFile other) {
			int order = sha1.compareTo(other.sha1);
			return order != 0 ? order : Long.compare(size, other.size);
		}
	}

	/** Number of unique (path, sha1, size) triples. */
	public int size() {
		int count = 0;
		for (NavigableSet<ContentFile> variants : files.values()) count += variants.size();
		return count;
	}

	/** The content token: the canonical identity of exactly this served file set. */
	public String token() {
		CanonicalEncoder encoder = new CanonicalEncoder().string(TOKEN_DOMAIN).integer(size());
		forEach((path, file) -> encoder.string(path).string(file.sha1()).longValue(file.size()));
		return encoder.sha1();
	}

	public void forEach(BiConsumer<String, ContentFile> consumer) {
		for (var entry : files.entrySet()) for (ContentFile file : entry.getValue()) consumer.accept(entry.getKey(), file);
	}

	public Set<String> objectHashes() {
		TreeSet<String> hashes = new TreeSet<>();
		for (NavigableSet<ContentFile> variants : files.values()) for (ContentFile file : variants) hashes.add(file.sha1());
		return hashes;
	}

	public NavigableSet<ContentFile> filesAt(String path) {
		NavigableSet<ContentFile> variants = files.get(path);
		return variants == null ? NO_FILES : variants;
	}

	/**
	 * Unique-path edit is {@code CHANGED}. A new variant at an existing path is {@code ADDED}. Removing one variant is
	 * {@code REMOVED}. Two groups sharing identical bytes at one path are one triple.
	 */
	public List<JournalEntry.Change> diff(ContentTree after) {
		Objects.requireNonNull(after, "after");
		List<JournalEntry.Change> changes = new ArrayList<>();
		TreeSet<String> paths = new TreeSet<>();
		paths.addAll(files.keySet());
		paths.addAll(after.files.keySet());
		for (String path : paths) {
			NavigableSet<ContentFile> before = filesAt(path);
			NavigableSet<ContentFile> next = after.filesAt(path);
			if (before.size() == 1 && next.size() == 1 && !before.first().equals(next.first())) {
				ContentFile oldFile = before.first();
				ContentFile newFile = next.first();
				changes.add(new JournalEntry.Change(path, oldFile.sha1(), oldFile.size(), newFile.sha1(), newFile.size()));
				continue;
			}
			for (ContentFile oldFile : before) if (!next.contains(oldFile)) changes.add(JournalEntry.Change.removed(path, oldFile.sha1(), oldFile.size()));
			for (ContentFile newFile : next) if (!before.contains(newFile)) changes.add(JournalEntry.Change.added(path, newFile.sha1(), newFile.size()));
		}
		return changes;
	}

	public static ContentTree fromManifest(GroupManifest manifest) {
		Objects.requireNonNull(manifest, "manifest");
		Builder builder = builder();
		for (var group : manifest.groups().entrySet()) {
			for (var file : group.getValue().files().entrySet()) builder.add(file.getKey(), new ContentFile(file.getValue().sha1(), file.getValue().size()));
		}
		return builder.build();
	}

	public static ContentTree empty() {
		return new ContentTree(new TreeMap<>());
	}

	public static Builder builder() {
		return new Builder();
	}

	/** The content token of the served file set described by a policy document. */
	public static String tokenOf(GroupManifest manifest) {
		return fromManifest(manifest).token();
	}

	public static final class Builder {
		private final TreeMap<String, TreeSet<ContentFile>> files = new TreeMap<>();

		public Builder add(String path, ContentFile file) {
			files.computeIfAbsent(path, ignored -> new TreeSet<>()).add(file);
			return this;
		}

		public Builder apply(JournalEntry.Change change) {
			Objects.requireNonNull(change, "change");
			return switch (change.kind()) {
				case ADDED -> add(change.path(), new ContentFile(change.toSha1(), change.toSize()));
				case REMOVED -> remove(change.path(), change.fromSha1(), change.fromSize());
				case CHANGED -> {
					remove(change.path(), change.fromSha1(), change.fromSize());
					add(change.path(), new ContentFile(change.toSha1(), change.toSize()));
					yield this;
				}
			};
		}

		public ContentTree build() {
			TreeMap<String, NavigableSet<ContentFile>> snapshot = new TreeMap<>();
			for (var entry : files.entrySet()) snapshot.put(entry.getKey(), entry.getValue());
			return new ContentTree(snapshot);
		}

		private Builder remove(String path, String sha1, long size) {
			TreeSet<ContentFile> variants = files.get(path);
			ContentFile file = new ContentFile(sha1, size);
			if (variants == null || !variants.remove(file)) throw new IllegalArgumentException("Journal change removes missing content for path '" + path + "'");
			if (variants.isEmpty()) files.remove(path);
			return this;
		}
	}
}
