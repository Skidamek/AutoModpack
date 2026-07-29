package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

import pl.skidam.automodpack_core.config.Jsons;

public record GroupManifest(
		String modpackId,
		String modpackName,
		String automodpackVersion,
		String loader,
		String loaderVersion,
		String mcVersion,
		NavigableMap<String, Group> groups,
		NavigableMap<String, SelectionTag> selectionTags,
		List<DeletionRequest> nonModpackFilesToDelete) {
	public GroupManifest {
		groups = immutableMap(groups);
		selectionTags = immutableMap(selectionTags);
		nonModpackFilesToDelete = nonModpackFilesToDelete == null ? List.of() : nonModpackFilesToDelete.stream().sorted().toList();
	}

	public Jsons.CompleteModpackContentFields toFields() {
		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		fields.modpackName = modpackName;
		fields.automodpackVersion = automodpackVersion;
		fields.loader = loader;
		fields.loaderVersion = loaderVersion;
		fields.mcVersion = mcVersion;

		Map<String, Jsons.CompleteModpackContentFields.ModpackGroupFields> serializedGroups = new LinkedHashMap<>();
		for (var entry : groups.entrySet()) {
			Group group = entry.getValue();
			Jsons.CompleteModpackContentFields.ModpackGroupFields serialized = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
			serialized.displayName = group.displayName();
			serialized.description = group.description();
			serialized.category = group.category();
			serialized.required = group.required();
			serialized.recommended = group.recommended();
			serialized.breaksWith = new LinkedHashSet<>(group.breaksWith());
			serialized.requires = new LinkedHashSet<>(group.requires());
			serialized.tags = new LinkedHashSet<>(group.tags());
			serialized.compatiblePlatforms = group.compatiblePlatforms().stream().map(ClientPlatform::id)
					.collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
			Map<String, Jsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
			for (var fileEntry : group.files().entrySet()) {
				GroupFile file = fileEntry.getValue();
				files.put(fileEntry.getKey(), new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(file.size()), file.type(), file.editable(),
						file.overwriteEditable(), file.forceCopy(), file.sha1(), file.murmur()));
			}
			serialized.files = files;
			serializedGroups.put(entry.getKey(), serialized);
		}
		fields.groups = serializedGroups;

		Map<String, Jsons.CompleteModpackContentFields.SelectionTagFields> serializedTags = new LinkedHashMap<>();
		for (var entry : selectionTags.entrySet()) {
			SelectionTag tag = entry.getValue();
			Jsons.CompleteModpackContentFields.SelectionTagFields serialized = new Jsons.CompleteModpackContentFields.SelectionTagFields();
			serialized.displayName = tag.displayName();
			serialized.description = tag.description();
			serialized.defaultSelected = tag.defaultSelected();
			serialized.serverForced = tag.serverForced();
			serializedTags.put(entry.getKey(), serialized);
		}
		fields.selectionTags = serializedTags;

		Set<Jsons.ModpackContentFields.FileToDelete> deletions = new LinkedHashSet<>();
		for (DeletionRequest deletion : nonModpackFilesToDelete)
			deletions.add(new Jsons.ModpackContentFields.FileToDelete(deletion.file(), deletion.sha1(), deletion.timestamp()));
		fields.nonModpackFilesToDelete = deletions;
		return fields;
	}

	private static <T> NavigableMap<String, T> immutableMap(Map<String, T> input) {
		TreeMap<String, T> sorted = new TreeMap<>();
		if (input != null) sorted.putAll(input);
		return Collections.unmodifiableNavigableMap(sorted);
	}

	private static NavigableSet<String> immutableSet(Collection<String> input) {
		TreeSet<String> sorted = new TreeSet<>();
		if (input != null) sorted.addAll(input);
		return Collections.unmodifiableNavigableSet(sorted);
	}

	private static Set<ClientPlatform> immutablePlatforms(Collection<ClientPlatform> input) {
		if (input == null || input.isEmpty()) return Set.of();
		return Collections.unmodifiableSet(EnumSet.copyOf(input));
	}

	public record Group(
			String displayName,
			String description,
			String category,
			boolean required,
			boolean recommended,
			NavigableSet<String> breaksWith,
			NavigableSet<String> requires,
			NavigableSet<String> tags,
			Set<ClientPlatform> compatiblePlatforms,
			NavigableMap<String, GroupFile> files) {
		public Group {
			displayName = displayName == null ? "" : displayName;
			description = description == null ? "" : description;
			category = category == null ? "" : category;
			breaksWith = immutableSet(breaksWith);
			requires = immutableSet(requires);
			tags = immutableSet(tags);
			compatiblePlatforms = immutablePlatforms(compatiblePlatforms);
			files = immutableMap(files);
		}

		public boolean supports(ClientPlatform platform) {
			return compatiblePlatforms.isEmpty() || compatiblePlatforms.contains(platform);
		}
	}

	public record SelectionTag(String displayName, String description, boolean defaultSelected, boolean serverForced) {
		public SelectionTag {
			displayName = displayName == null ? "" : displayName;
			description = description == null ? "" : description;
		}
	}

	public record GroupFile(long size, String type, boolean editable, boolean overwriteEditable, boolean forceCopy, String sha1, String murmur) {
		public boolean sameEffectiveState(GroupFile other) {
			return other != null && size == other.size && editable == other.editable && overwriteEditable == other.overwriteEditable
					&& forceCopy == other.forceCopy && Objects.equals(type, other.type) && sha1.equalsIgnoreCase(other.sha1);
		}
	}

	public record DeletionRequest(String file, String sha1, String timestamp) implements Comparable<DeletionRequest> {
		@Override
		public int compareTo(DeletionRequest other) {
			int timestampOrder = Objects.toString(timestamp, "").compareTo(Objects.toString(other.timestamp, ""));
			if (timestampOrder != 0) return timestampOrder;
			int fileOrder = Objects.toString(file, "").compareTo(Objects.toString(other.file, ""));
			return fileOrder != 0 ? fileOrder : Objects.toString(sha1, "").compareTo(Objects.toString(other.sha1, ""));
		}
	}
}
