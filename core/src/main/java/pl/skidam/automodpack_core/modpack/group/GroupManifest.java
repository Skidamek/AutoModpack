package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

import pl.skidam.automodpack_core.config.ModpackJsons;

public record GroupManifest(
		String modpackId,
		String modpackName,
		String automodpackVersion,
		String loader,
		String loaderVersion,
		String mcVersion,
		NavigableMap<String, Group> groups) {
	public GroupManifest {
		groups = immutableMap(groups);
	}

	public ModpackJsons.CompleteModpackContentFields toFields() {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		fields.modpackName = modpackName;
		fields.automodpackVersion = automodpackVersion;
		fields.loader = loader;
		fields.loaderVersion = loaderVersion;
		fields.mcVersion = mcVersion;

		Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields> serializedGroups = new LinkedHashMap<>();
		for (var entry : groups.entrySet()) {
			Group group = entry.getValue();
			ModpackJsons.CompleteModpackContentFields.ModpackGroupFields serialized = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
			serialized.displayName = group.displayName();
			serialized.description = group.description();
			serialized.category = group.category();
			serialized.icon = group.icon();
			serialized.required = group.required();
			serialized.defaultSelected = group.defaultSelected();
			serialized.breaksWith = new LinkedHashSet<>(group.breaksWith());
			serialized.requires = new LinkedHashSet<>(group.requires());
			serialized.compatiblePlatforms = group.compatiblePlatforms().stream().map(ClientPlatform::id)
					.collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
			Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
			for (var fileEntry : group.files().entrySet()) {
				GroupFile file = fileEntry.getValue();
				files.put(fileEntry.getKey(), new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(file.size()), file.type(), file.editable(),
						file.overwriteEditable(), file.sha1(), file.murmur()));
			}
			serialized.files = files;
			serializedGroups.put(entry.getKey(), serialized);
		}
		fields.groups = serializedGroups;

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
			String icon,
			boolean required,
			boolean defaultSelected,
			NavigableSet<String> breaksWith,
			NavigableSet<String> requires,
			Set<ClientPlatform> compatiblePlatforms,
			NavigableMap<String, GroupFile> files) {
		public Group {
			displayName = displayName == null ? "" : displayName;
			description = description == null ? "" : description;
			category = category == null ? "" : category;
			icon = icon == null ? "" : icon;
			breaksWith = immutableSet(breaksWith);
			requires = immutableSet(requires);
			compatiblePlatforms = immutablePlatforms(compatiblePlatforms);
			files = immutableMap(files);
		}

		public boolean supports(ClientPlatform platform) {
			return compatiblePlatforms.isEmpty() || compatiblePlatforms.contains(platform);
		}
	}
	public record GroupFile(long size, String type, boolean editable, boolean overwriteEditable, String sha1, String murmur) {
		public boolean sameEffectiveState(GroupFile other) {
			return other != null && size == other.size && editable == other.editable && overwriteEditable == other.overwriteEditable
					&& Objects.equals(type, other.type) && sha1.equalsIgnoreCase(other.sha1);
		}
	}
}
