package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

import pl.skidam.automodpack_core.config.ModpackJsons;

/**
 * The flat group model of one generation. {@link Group#category()} holds the category NAME (never empty); category order is
 * the first-occurrence order of {@code category} while iterating {@code groups}, since every category's groups are contiguous
 * in it. Insertion order carries the declared category and group order, so every map here preserves it.
 */
public record GroupManifest(
		String modpackId,
		String modpackName,
		String automodpackVersion,
		String loader,
		String loaderVersion,
		String mcVersion,
		Map<String, Group> groups) {
	public GroupManifest {
		groups = immutableMap(groups);
	}

	/** The platforms this manifest's groups declare, in id order; most groups declare none. */
	public Set<ClientPlatform> declaredPlatforms() {
		Set<ClientPlatform> platforms = new TreeSet<>();
		for (Group group : groups.values()) platforms.addAll(group.compatiblePlatforms());
		return platforms;
	}

	public ModpackJsons.CompleteModpackContentFields toFields() {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		fields.modpackName = modpackName;
		fields.automodpackVersion = automodpackVersion;
		fields.loader = loader;
		fields.loaderVersion = loaderVersion;
		fields.mcVersion = mcVersion;

		Map<String, Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields>> serializedCategories = new LinkedHashMap<>();
		for (var entry : groups.entrySet()) {
			Group group = entry.getValue();
			ModpackJsons.CompleteModpackContentFields.ModpackGroupFields serialized = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
			serialized.displayName = group.displayName();
			serialized.description = group.description();
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
						file.sha1(), file.murmur()));
			}
			serialized.files = files;
			serializedCategories.computeIfAbsent(group.category(), ignored -> new LinkedHashMap<>()).put(entry.getKey(), serialized);
		}
		fields.categories = serializedCategories;

		return fields;
	}

	private static <T> Map<String, T> immutableMap(Map<String, T> input) {
		Map<String, T> ordered = new LinkedHashMap<>();
		if (input != null) ordered.putAll(input);
		return Collections.unmodifiableMap(ordered);
	}

	private static Set<String> immutableSet(Collection<String> input) {
		Set<String> ordered = new LinkedHashSet<>();
		if (input != null) ordered.addAll(input);
		return Collections.unmodifiableSet(ordered);
	}

	private static Set<ClientPlatform> immutablePlatforms(Collection<ClientPlatform> input) {
		if (input == null || input.isEmpty()) return Set.of();
		Set<ClientPlatform> platforms = new TreeSet<>();
		platforms.addAll(input);
		return Collections.unmodifiableSet(platforms);
	}

	public record Group(
			String displayName,
			String description,
			String category,
			boolean required,
			boolean defaultSelected,
			Set<String> breaksWith,
			Set<String> requires,
			Set<ClientPlatform> compatiblePlatforms,
			Map<String, GroupFile> files) {
		public Group {
			displayName = displayName == null ? "" : displayName;
			description = description == null ? "" : description;
			category = category == null ? "" : category;
			breaksWith = immutableSet(breaksWith);
			requires = immutableSet(requires);
			compatiblePlatforms = immutablePlatforms(compatiblePlatforms);
			files = immutableMap(files);
		}

		/** Platform-agnostic groups support everything; platform-specific ones need a matching detected or chosen platform. */
		public boolean supports(ClientPlatform platform) {
			return compatiblePlatforms.isEmpty() || (platform != null && compatiblePlatforms.contains(platform));
		}

		public boolean hasSameMetadata(Group other) {
			return other != null && Objects.equals(displayName, other.displayName) && Objects.equals(description, other.description)
					&& Objects.equals(category, other.category) && required == other.required
					&& defaultSelected == other.defaultSelected && Objects.equals(breaksWith, other.breaksWith) && Objects.equals(requires, other.requires)
					&& Objects.equals(compatiblePlatforms, other.compatiblePlatforms);
		}
	}
	public record GroupFile(long size, String type, boolean editable, String sha1, String murmur) {
		public boolean sameEffectiveState(GroupFile other) {
			return other != null && size == other.size && editable == other.editable
					&& Objects.equals(type, other.type) && sha1.equalsIgnoreCase(other.sha1);
		}
	}
}
