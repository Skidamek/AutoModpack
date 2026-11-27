package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

import pl.skidam.automodpack_core.config.ModpackJsons;

/**
 * The flat group model of one generation: optional categories plus flat groups referencing them by id.
 * Insertion order carries the declared category and group order.
 */
public record GroupManifest(
		String modpackId,
		String modpackName,
		String automodpackVersion,
		String loader,
		String loaderVersion,
		String mcVersion,
		Map<String, Category> categories,
		Map<String, Group> groups) {
	public GroupManifest {
		categories = immutableMap(categories);
		groups = immutableMap(groups);
	}

	/** The platforms this manifest's groups declare, in id order; most groups declare none. */
	public Set<ClientPlatform> declaredPlatforms() {
		Set<ClientPlatform> platforms = new TreeSet<>();
		for (Group group : groups.values()) platforms.addAll(group.compatiblePlatforms());
		return platforms;
	}

	public boolean hasDeclaredPlatforms() {
		return groups.values().stream().anyMatch(group -> !group.compatiblePlatforms().isEmpty());
	}

	public boolean hasChoosableGroups() {
		return groups.values().stream().anyMatch(group -> group.selection().choosable());
	}

	public Category categoryOf(String groupId) {
		Group group = groups.get(groupId);
		if (group == null || group.category().isEmpty()) return null;
		return categories.get(group.category());
	}

	public String displayName(String groupId) {
		Group group = groups.get(groupId);
		return group == null || group.name().isBlank() ? groupId : group.name();
	}

	public String categoryDisplayName(String categoryId) {
		Category category = categories.get(categoryId);
		return category == null || category.name().isBlank() ? categoryId : category.name();
	}

	public ModpackJsons.CompleteModpackContentFields toFields() {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		fields.modpackName = modpackName;
		fields.automodpackVersion = automodpackVersion;
		fields.loader = loader;
		fields.loaderVersion = loaderVersion;
		fields.mcVersion = mcVersion;

		Map<String, ModpackJsons.CompleteModpackContentFields.CategoryFields> serializedCategories = new LinkedHashMap<>();
		for (var entry : categories.entrySet()) {
			Category category = entry.getValue();
			ModpackJsons.CompleteModpackContentFields.CategoryFields serialized = new ModpackJsons.CompleteModpackContentFields.CategoryFields();
			serialized.name = category.name();
			serialized.description = category.description();
			serialized.pick = category.pick();
			serializedCategories.put(entry.getKey(), serialized);
		}
		fields.categories = serializedCategories;

		Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields> serializedGroups = new LinkedHashMap<>();
		for (var entry : groups.entrySet()) {
			Group group = entry.getValue();
			ModpackJsons.CompleteModpackContentFields.ModpackGroupFields serialized = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
			serialized.name = group.name();
			serialized.description = group.description();
			serialized.category = group.category();
			serialized.selection = group.selection();
			serialized.requires = new LinkedHashSet<>(group.requires());
			serialized.compatiblePlatforms = group.compatiblePlatforms().stream().map(ClientPlatform::id)
					.collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
			Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
			for (var fileEntry : group.files().entrySet()) files.put(fileEntry.getKey(), publishedFile(fileEntry.getKey(), fileEntry.getValue()));
			serialized.files = files;
			serializedGroups.put(entry.getKey(), serialized);
		}
		fields.groups = serializedGroups;

		return fields;
	}

	public static ModpackJsons.CompleteModpackContentFields.GroupFileFields publishedFile(String path, GroupFile file) {
		return new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(file.size()), file.editable(), file.sha1(), file.murmur());
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

	public record Category(String name, String description, CategoryPickMode pick) {
		public Category {
			name = name == null ? "" : name;
			description = description == null ? "" : description;
			pick = Objects.requireNonNull(pick, "pick");
		}

		public boolean hasSameMetadata(Category other) {
			return other != null && Objects.equals(name, other.name) && Objects.equals(description, other.description) && pick == other.pick;
		}
	}

	public record Group(
			String name,
			String description,
			String category,
			GroupSelectionMode selection,
			Set<String> requires,
			Set<ClientPlatform> compatiblePlatforms,
			Map<String, GroupFile> files) {
		public Group {
			name = name == null ? "" : name;
			description = description == null ? "" : description;
			category = category == null ? "" : category;
			selection = Objects.requireNonNull(selection, "selection");
			requires = immutableSet(requires);
			compatiblePlatforms = immutablePlatforms(compatiblePlatforms);
			files = immutableMap(files);
		}

		public boolean alwaysInstalled() {
			return selection.alwaysInstalled();
		}

		public boolean arrivesSelected() {
			return selection.arrivesSelected();
		}

		/** Platform-agnostic groups support everything; platform-specific ones need a matching detected or chosen platform. */
		public boolean supports(ClientPlatform platform) {
			return compatiblePlatforms.isEmpty() || (platform != null && compatiblePlatforms.contains(platform));
		}

		public boolean hasSameMetadata(Group other) {
			return other != null && Objects.equals(name, other.name) && Objects.equals(description, other.description)
					&& Objects.equals(category, other.category) && selection == other.selection
					&& Objects.equals(requires, other.requires) && Objects.equals(compatiblePlatforms, other.compatiblePlatforms);
		}
	}

	public record GroupFile(long size, boolean editable, String sha1, String murmur) {
		public boolean sameEffectiveState(GroupFile other) {
			return other != null && size == other.size && editable == other.editable && sha1.equalsIgnoreCase(other.sha1);
		}
	}
}
