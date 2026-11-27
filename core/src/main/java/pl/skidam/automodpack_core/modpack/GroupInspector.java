package pl.skidam.automodpack_core.modpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.config.TomlConfigs;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.ModpackContentType;
import pl.skidam.automodpack_core.utils.ByteFormat;

/** Operator-facing views and folder import helpers for the group model. */
public final class GroupInspector {
	private GroupInspector() {}

	public record ImportResult(List<String> imported, List<String> skipped, List<String> warnings) {}

	/** One summary line per group grouped cleanly by category (if any); {@code published} may be null. */
	public static List<String> overview(ServerConfigJsons.ModpackFields modpack, GroupManifest published) {
		Map<String, GroupManifest.Group> publishedGroups = publishedGroups(published);
		Map<String, ServerConfigJsons.GroupDeclaration> groups = modpack == null || modpack.groups == null ? Map.of() : modpack.groups;
		Map<String, ServerConfigJsons.CategoryDeclaration> categories = modpack == null || modpack.categories == null ? Map.of() : modpack.categories;
		List<String> lines = new ArrayList<>();
		lines.add("Modpack groups: " + groups.size() + " groups" + (categories.isEmpty() ? "" : ", " + categories.size() + " categories"));

		Map<String, List<Map.Entry<String, ServerConfigJsons.GroupDeclaration>>> byCategory = new LinkedHashMap<>();
		for (var entry : groups.entrySet()) {
			String cat = entry.getValue() == null || entry.getValue().category == null ? "" : entry.getValue().category;
			byCategory.computeIfAbsent(cat, ignored -> new ArrayList<>()).add(entry);
		}

		List<Map.Entry<String, ServerConfigJsons.GroupDeclaration>> uncategorized = byCategory.getOrDefault("", List.of());
		if (!uncategorized.isEmpty()) {
			String indent = categories.isEmpty() ? "  " : "    ";
			if (!categories.isEmpty()) lines.add("  (uncategorized):");
			for (var group : uncategorized) lines.add(indent + summaryLine(group.getKey(), group.getValue(), publishedGroups.get(group.getKey())));
		}
		for (var catEntry : categories.entrySet()) {
			String catId = catEntry.getKey();
			ServerConfigJsons.CategoryDeclaration cat = catEntry.getValue();
			String label = cat != null && cat.name != null && !cat.name.isBlank() ? cat.name + " (" + catId + ")" : catId;
			String pickSuffix = cat != null && cat.pick != null && cat.pick.isExclusive() ? " [" + cat.pick.name().toLowerCase(Locale.ROOT) + "]" : "";
			lines.add("  " + label + pickSuffix + ":");
			for (var group : byCategory.getOrDefault(catId, List.of())) lines.add("    " + summaryLine(group.getKey(), group.getValue(), publishedGroups.get(group.getKey())));
		}
		return lines;
	}

	/** The detail view of one group, or empty when neither the config nor the published manifest knows the id. */
	public static Optional<List<String>> detail(ServerConfigJsons.ModpackFields modpack, String groupId, GroupManifest published) {
		ServerConfigJsons.GroupDeclaration declaration = modpack != null && modpack.groups != null ? modpack.groups.get(groupId) : null;
		GroupManifest.Group publishedGroup = publishedGroups(published).get(groupId);
		if (declaration == null && publishedGroup == null) return Optional.empty();

		List<String> lines = new ArrayList<>();
		if (declaration == null) {
			lines.add("Group " + groupId + " is published but missing from the server config");
			addField(lines, "Category", publishedGroup.category());
			addField(lines, "Name", publishedGroup.name());
			addField(lines, "Description", publishedGroup.description());
			lines.add("Selection: " + selectionLabel(publishedGroup.selection()));
			addJoined(lines, "Platforms", platforms(publishedGroup));
			addJoined(lines, "Requires", publishedGroup.requires());
		} else {
			String catSuffix = declaration.category == null || declaration.category.isBlank() ? "" : " (category " + declaration.category + ")";
			lines.add("Group " + groupId + catSuffix);
			addField(lines, "Name", declaration.name);
			addField(lines, "Description", declaration.description);
			lines.add("Selection: " + selectionLabel(declaration.selection));
			lines.add("Auto-exclude server mods: " + (declaration.autoExcludeServerSideMods != null ? declaration.autoExcludeServerSideMods : "inherited (" + modpack.autoExcludeServerSideMods + ")"));
			addJoined(lines, "Platforms", declaration.compatiblePlatforms);
			addJoined(lines, "Requires", declaration.requires);
			addRules(lines, "From server", declaration.fromServer);
			addRules(lines, "Exclude", declaration.exclude);
			addRules(lines, "Editable", declaration.editable);
		}
		if (publishedGroup == null) lines.add("Published: (not published yet)");
		else filesSection(lines, publishedGroup);
		return Optional.of(lines);
	}

	/** Imports one named folder or all undeclared non-empty folders under {@code groupRoot} into {@code serverConfigFile}. */
	public static ImportResult importFolders(Path serverConfigFile, Path groupRoot, ServerConfigJsons.ModpackFields modpack, String singleFolder) throws IOException {
		List<String> imported = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		if (!Files.isDirectory(groupRoot)) {
			warnings.add("host-modpack directory does not exist: " + groupRoot);
			return new ImportResult(imported, skipped, warnings);
		}

		Set<String> declared = modpack != null && modpack.groups != null ? modpack.groups.keySet() : Set.of();
		Map<String, ServerConfigJsons.GroupDeclaration> toInsert = new TreeMap<>();
		if (singleFolder != null && !singleFolder.isBlank()) {
			Path target = groupRoot.resolve(singleFolder).normalize();
			if (!GroupManifestValidator.isValidIdentifier(singleFolder)) {
				warnings.add("Invalid group ID '" + singleFolder + "' (allowed: lowercase [a-z0-9][a-z0-9_-]*, no reserved names)");
			} else if (!target.startsWith(groupRoot) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
				warnings.add("Folder does not exist under host-modpack/: " + singleFolder);
			} else if (declared.contains(singleFolder)) {
				skipped.add(singleFolder + " (already declared)");
			} else {
				toInsert.put(singleFolder, new ServerConfigJsons.GroupDeclaration());
				imported.add(singleFolder);
			}
		} else {
			List<Path> dirs;
			try (Stream<Path> stream = Files.list(groupRoot)) {
				dirs = stream.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
			}
			for (Path dir : dirs) {
				String name = dir.getFileName().toString();
				if (declared.contains(name)) continue;
				if (!GroupManifestValidator.isValidIdentifier(name)) {
					warnings.add("Skipped invalid folder name '" + name + "'");
					continue;
				}
				if (isDirectoryEmpty(dir)) {
					skipped.add(name + " (empty folder)");
					continue;
				}
				toInsert.put(name, new ServerConfigJsons.GroupDeclaration());
				imported.add(name);
			}
		}

		if (!toInsert.isEmpty()) TomlConfigs.insertImportedGroups(serverConfigFile, toInsert);
		return new ImportResult(List.copyOf(imported), List.copyOf(skipped), List.copyOf(warnings));
	}

	private static boolean isDirectoryEmpty(Path directory) throws IOException {
		try (Stream<Path> walk = Files.walk(directory)) {
			return walk.noneMatch(Files::isRegularFile);
		}
	}

	private static String summaryLine(String groupId, ServerConfigJsons.GroupDeclaration declaration, GroupManifest.Group published) {
		StringBuilder line = new StringBuilder(groupId);
		if (declaration.name != null && !declaration.name.isBlank()) line.append(" - ").append(declaration.name);
		line.append(" [").append(selectionLabel(declaration.selection)).append("]");
		if (declaration.compatiblePlatforms != null && !declaration.compatiblePlatforms.isEmpty()) line.append(" [").append(joined(declaration.compatiblePlatforms)).append("]");
		if (published == null) line.append(" (not published yet)");
		else line.append(" ").append(published.files().size()).append(" files, ").append(ByteFormat.formatSize(totalSize(published)));
		return line.toString();
	}

	private static void filesSection(List<String> lines, GroupManifest.Group group) {
		lines.add("Published files: " + group.files().size() + " files, " + ByteFormat.formatSize(totalSize(group)));
		Map<String, long[]> byType = new TreeMap<>();
		for (var file : group.files().entrySet()) {
			long[] countAndBytes = byType.computeIfAbsent(ModpackContentType.kindForPath(file.getKey()), ignored -> new long[2]);
			countAndBytes[0]++;
			countAndBytes[1] += file.getValue().size();
		}
		for (var type : byType.entrySet())
			lines.add("  " + type.getKey() + ": " + type.getValue()[0] + " files, " + ByteFormat.formatSize(type.getValue()[1]));
		lines.add("All files:");
		for (var file : new TreeMap<>(group.files()).entrySet())
			lines.add("  " + file.getKey() + " - " + ByteFormat.formatSize(file.getValue().size()));
	}

	private static Map<String, GroupManifest.Group> publishedGroups(GroupManifest published) {
		return published == null ? Map.of() : published.groups();
	}

	private static Set<String> platforms(GroupManifest.Group group) {
		return group.compatiblePlatforms().stream().map(ClientPlatform::id).collect(Collectors.toCollection(TreeSet::new));
	}

	private static long totalSize(GroupManifest.Group group) {
		long total = 0;
		for (GroupManifest.GroupFile file : group.files().values()) total += file.size();
		return total;
	}

	private static String selectionLabel(GroupSelectionMode selection) {
		return selection == null ? "optional" : selection.name().toLowerCase(Locale.ROOT);
	}

	private static void addField(List<String> lines, String label, String value) {
		if (value != null && !value.isBlank()) lines.add(label + ": " + value);
	}

	private static void addJoined(List<String> lines, String label, Set<String> values) {
		if (values != null && !values.isEmpty()) lines.add(label + ": " + joined(values));
	}

	/** Rule sets always print, an empty one as {@code (none)}: an empty rule is a deliberate posture, not missing data. */
	private static void addRules(List<String> lines, String label, Set<String> rules) {
		lines.add(label + ": " + (rules == null || rules.isEmpty() ? "(none)" : joined(rules)));
	}

	private static String joined(Set<String> values) {
		return String.join(", ", new TreeSet<>(values));
	}
}
