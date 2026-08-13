package pl.skidam.automodpack_core.modpack.generation;

import java.util.*;
import java.util.function.BiPredicate;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;

/** A deterministic, complete-catalogue diff between two generation states. */
public record GenerationDiff(
		List<FileChange> files,
		MetadataSummary packMetadata,
		MetadataSummary groupMetadata) {
	public GenerationDiff {
		files = files == null
				? List.of()
				: files.stream().sorted(Comparator.comparing(FileChange::groupId).thenComparing(FileChange::logicalPath)
						.thenComparingInt(change -> change.classification().ordinal()).thenComparing(change -> Objects.toString(change.before(), ""))
						.thenComparing(change -> Objects.toString(change.after(), ""))).toList();
		packMetadata = Objects.requireNonNull(packMetadata);
		groupMetadata = Objects.requireNonNull(groupMetadata);
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
		return new GenerationDiff(changes, packSummary(parent, child), groupSummary(parent, child));
	}

	public boolean isEmpty() {
		return changeSet().changes().isEmpty() && changeSet().effects().isEmpty();
	}

	public Summary summary() {
		ChangeSet.Summary summary = changeSet().summary();
		return new Summary(summary.addedFiles(), summary.modifiedFiles(), summary.removedFiles(), summary.metadataOnlyFiles(), summary.effectCount());
	}

	/** Returns the canonical logical change model used by previews, history, and changelogs. */
	public ChangeSet changeSet() {
		List<ChangeSet.Change> changes = new ArrayList<>(files.size());
		for (FileChange change : files) {
			GroupManifest.GroupFile before = change.before();
			GroupManifest.GroupFile after = change.after();
			long size = after == null ? before.size() : after.size();
			String contentKind = after == null ? before.type() : after.type();
			ChangeSet.Occurrence occurrence = new ChangeSet.Occurrence("catalogue", change.logicalPath(), size,
					before == null ? null : before.sha1(), after == null ? null : after.sha1(), contentKind, List.of(change.groupId()), List.of());
			changes.add(new ChangeSet.Change(change.logicalPath(), canonicalKind(change.classification()), List.of(occurrence)));
		}
		List<ChangeSet.Effect> effects = new ArrayList<>();
		appendMetadataEffects(effects, "pack", packMetadata);
		appendMetadataEffects(effects, "group", groupMetadata);
		return ChangeSet.of(changes, effects);
	}

	/** Returns deterministic text for an operator-facing generation change summary. */
	public List<String> humanReadableChanges() {
		List<String> changes = new ArrayList<>();
		appendMetadataChanges(changes, "pack metadata", packMetadata);
		appendMetadataChanges(changes, "group", groupMetadata);
		for (FileChange change : files) {
			changes.add(change.classification().action() + " file '" + change.groupId() + "/" + change.logicalPath() + "'");
		}
		return changes.isEmpty() ? List.of("No catalogue changes.") : List.copyOf(changes);
	}

	private static void appendMetadataChanges(List<String> changes, String kind, MetadataSummary summary) {
		for (String value : summary.added()) changes.add("Added " + kind + " '" + value + "'");
		for (String value : summary.modified()) changes.add("Changed " + kind + " '" + value + "'");
		for (String value : summary.removed()) changes.add("Removed " + kind + " '" + value + "'");
	}

	private static void appendMetadataEffects(List<ChangeSet.Effect> effects, String kind, MetadataSummary summary) {
		for (String value : summary.added()) effects.add(new ChangeSet.Effect(kind + ".added", value));
		for (String value : summary.modified()) effects.add(new ChangeSet.Effect(kind + ".modified", value));
		for (String value : summary.removed()) effects.add(new ChangeSet.Effect(kind + ".removed", value));
	}

	private static ChangeSet.Kind canonicalKind(FileClassification classification) {
		return switch (classification) {
			case ADDED -> ChangeSet.Kind.ADDED;
			case MODIFIED -> ChangeSet.Kind.MODIFIED;
			case REMOVED -> ChangeSet.Kind.REMOVED;
			case METADATA_ONLY -> ChangeSet.Kind.METADATA_ONLY;
		};
	}

	public enum FileClassification {
		ADDED("Added", false, true, "Added file change endpoints do not match classification"),
		MODIFIED("Changed", true, true, "Changed file endpoints are incomplete"),
		REMOVED("Removed", true, false, "Removed file change endpoints do not match classification"),
		METADATA_ONLY("Changed metadata for", true, true, "Changed file endpoints are incomplete");

		private final String action;
		private final boolean beforeRequired;
		private final boolean afterRequired;
		private final String endpointError;

		FileClassification(String action, boolean beforeRequired, boolean afterRequired, String endpointError) {
			this.action = action;
			this.beforeRequired = beforeRequired;
			this.afterRequired = afterRequired;
			this.endpointError = endpointError;
		}

		private String action() {
			return action;
		}

		private boolean validEndpoints(GroupManifest.GroupFile before, GroupManifest.GroupFile after) {
			return (before != null) == beforeRequired && (after != null) == afterRequired;
		}

		private String endpointError() {
			return endpointError;
		}
	}

	public record FileChange(String groupId, String logicalPath, FileClassification classification, GroupManifest.GroupFile before,
			GroupManifest.GroupFile after) {
		public FileChange {
			groupId = Objects.requireNonNull(groupId);
			logicalPath = Objects.requireNonNull(logicalPath);
			classification = Objects.requireNonNull(classification);
			if (!classification.validEndpoints(before, after)) throw new IllegalArgumentException(classification.endpointError());
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
		return compareKeys(before, child.groups(), GroupManifest.Group::hasSameMetadata);
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
