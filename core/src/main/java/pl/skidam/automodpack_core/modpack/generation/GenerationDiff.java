package pl.skidam.automodpack_core.modpack.generation;

import java.util.*;
import java.util.function.BiPredicate;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;

/** A deterministic, complete-catalogue diff between two generation states. */
public record GenerationDiff(
		List<FileChange> files,
		MetadataSummary packMetadata,
		MetadataSummary groupMetadata,
		MetadataSummary selectionTagMetadata) {
	public GenerationDiff {
		files = files == null
				? List.of()
				: files.stream().sorted(Comparator.comparing(FileChange::groupId).thenComparing(FileChange::logicalPath)
						.thenComparingInt(change -> change.classification().ordinal()).thenComparing(change -> Objects.toString(change.before(), ""))
						.thenComparing(change -> Objects.toString(change.after(), ""))).toList();
		packMetadata = Objects.requireNonNull(packMetadata);
		groupMetadata = Objects.requireNonNull(groupMetadata);
		selectionTagMetadata = Objects.requireNonNull(selectionTagMetadata);
	}

	public static GenerationDiff between(GroupManifest parent, GroupManifest child) {
		Objects.requireNonNull(child, "child");
		Map<FileKey, GroupManifest.GroupFile> before = files(parent);
		Map<FileKey, GroupManifest.GroupFile> after = files(child);
		List<FileChange> changes = new ArrayList<>();
		TreeSet<FileKey> keys = new TreeSet<>();
		keys.addAll(before.keySet());
		keys.addAll(after.keySet());
		for (FileKey key : keys) {
			GroupManifest.GroupFile oldFile = before.get(key);
			GroupManifest.GroupFile newFile = after.get(key);
			FileClassification classification;
			if (oldFile == null) classification = FileClassification.ADDED;
			else if (newFile == null) classification = FileClassification.REMOVED;
			else if (!sameBytes(oldFile, newFile)) classification = FileClassification.MODIFIED;
			else if (!oldFile.equals(newFile)) classification = FileClassification.METADATA_ONLY;
			else continue;
			changes.add(new FileChange(key.groupId(), key.logicalPath(), classification, oldFile, newFile));
		}
		return new GenerationDiff(changes, packSummary(parent, child), groupSummary(parent, child), tagSummary(parent, child));
	}

	public boolean isEmpty() {
		return files.isEmpty() && packMetadata.isEmpty() && groupMetadata.isEmpty() && selectionTagMetadata.isEmpty();
	}

	public Summary summary() {
		int added = 0;
		int modified = 0;
		int removed = 0;
		int metadataOnly = 0;
		for (FileChange change : files) {
			switch (change.classification()) {
				case ADDED -> added++;
				case MODIFIED -> modified++;
				case REMOVED -> removed++;
				case METADATA_ONLY -> metadataOnly++;
			}
		}
		return new Summary(added, modified, removed, metadataOnly, packMetadata.changedCount() + groupMetadata.changedCount()
				+ selectionTagMetadata.changedCount());
	}

	public enum FileClassification {
		ADDED, MODIFIED, REMOVED, METADATA_ONLY
	}

	public record FileChange(String groupId, String logicalPath, FileClassification classification, GroupManifest.GroupFile before,
			GroupManifest.GroupFile after) {
		public FileChange {
			groupId = Objects.requireNonNull(groupId);
			logicalPath = Objects.requireNonNull(logicalPath);
			classification = Objects.requireNonNull(classification);
			switch (classification) {
				case ADDED -> {
					if (before != null || after == null) throw new IllegalArgumentException("Added file change endpoints do not match classification");
				}
				case REMOVED -> {
					if (before == null || after != null) throw new IllegalArgumentException("Removed file change endpoints do not match classification");
				}
				case MODIFIED, METADATA_ONLY -> {
					if (before == null || after == null) throw new IllegalArgumentException("Changed file endpoints are incomplete");
				}
			}
		}
	}

	public record MetadataSummary(List<String> added, List<String> modified, List<String> removed) {
		public MetadataSummary {
			added = sorted(added);
			modified = sorted(modified);
			removed = sorted(removed);
		}

		public boolean isEmpty() {
			return added.isEmpty() && modified.isEmpty() && removed.isEmpty();
		}

		public int changedCount() {
			return added.size() + modified.size() + removed.size();
		}
	}

	public record Summary(int addedFiles, int modifiedFiles, int removedFiles, int metadataOnlyFiles, int metadataChanges) {}

	private static MetadataSummary packSummary(GroupManifest parent, GroupManifest child) {
		if (parent == null) return new MetadataSummary(List.of("pack"), List.of(), List.of());
		List<String> modified = new ArrayList<>();
		if (!Objects.equals(parent.modpackId(), child.modpackId())) modified.add("modpackId");
		if (!Objects.equals(parent.modpackName(), child.modpackName())) modified.add("modpackName");
		if (!Objects.equals(parent.automodpackVersion(), child.automodpackVersion())) modified.add("automodpackVersion");
		if (!Objects.equals(parent.loader(), child.loader())) modified.add("loader");
		if (!Objects.equals(parent.loaderVersion(), child.loaderVersion())) modified.add("loaderVersion");
		if (!Objects.equals(parent.mcVersion(), child.mcVersion())) modified.add("mcVersion");
		return new MetadataSummary(List.of(), modified, List.of());
	}

	private static MetadataSummary groupSummary(GroupManifest parent, GroupManifest child) {
		Map<String, GroupManifest.Group> before = parent == null ? Map.of() : parent.groups();
		return compareKeys(before, child.groups(), GenerationDiff::sameGroupMetadata);
	}

	private static MetadataSummary tagSummary(GroupManifest parent, GroupManifest child) {
		Map<String, GroupManifest.SelectionTag> before = parent == null ? Map.of() : parent.selectionTags();
		return compareKeys(before, child.selectionTags(), Objects::equals);
	}

	private static <T> MetadataSummary compareKeys(Map<String, T> before, Map<String, T> after, BiPredicate<T, T> equal) {
		List<String> added = new ArrayList<>();
		List<String> modified = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		TreeSet<String> keys = new TreeSet<>();
		keys.addAll(before.keySet());
		keys.addAll(after.keySet());
		for (String key : keys) {
			if (!before.containsKey(key)) added.add(key);
			else if (!after.containsKey(key)) removed.add(key);
			else if (!equal.test(before.get(key), after.get(key))) modified.add(key);
		}
		return new MetadataSummary(added, modified, removed);
	}

	private static boolean sameGroupMetadata(GroupManifest.Group before, GroupManifest.Group after) {
		return Objects.equals(before.displayName(), after.displayName()) && Objects.equals(before.description(), after.description())
				&& Objects.equals(before.category(), after.category()) && Objects.equals(before.projectUrl(), after.projectUrl())
				&& Objects.equals(before.sourceUrl(), after.sourceUrl()) && before.required() == after.required() && before.recommended() == after.recommended()
				&& Objects.equals(before.breaksWith(), after.breaksWith()) && Objects.equals(before.requires(), after.requires())
				&& Objects.equals(before.tags(), after.tags()) && Objects.equals(before.compatiblePlatforms(), after.compatiblePlatforms());
	}

	private static boolean sameBytes(GroupManifest.GroupFile before, GroupManifest.GroupFile after) {
		return before.size() == after.size() && before.sha1().equalsIgnoreCase(after.sha1());
	}

	private static Map<FileKey, GroupManifest.GroupFile> files(GroupManifest manifest) {
		if (manifest == null) return Map.of();
		TreeMap<FileKey, GroupManifest.GroupFile> result = new TreeMap<>();
		for (var group : manifest.groups().entrySet())
			for (var file : group.getValue().files().entrySet())
				result.put(new FileKey(group.getKey(), file.getKey()), file.getValue());
		return result;
	}

	private record FileKey(String groupId, String logicalPath) implements Comparable<FileKey> {
		@Override
		public int compareTo(FileKey other) {
			int group = groupId.compareTo(other.groupId);
			return group != 0 ? group : logicalPath.compareTo(other.logicalPath);
		}
	}

	private static List<String> sorted(Collection<String> values) {
		return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values.stream().sorted().toList()));
	}
}
