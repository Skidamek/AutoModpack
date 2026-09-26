package pl.skidam.automodpack_core.modpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.utils.ByteFormat;

/** Operator-facing text views of the group model: the config declarations plus whatever generation is published. */
public final class GroupInspector {
	private GroupInspector() {}

	/** One line per group under its category heading; {@code published} may be null when nothing was generated yet. */
	public static List<String> overview(Map<String, Map<String, ServerConfigJsons.GroupDeclaration>> modpack, GroupManifest published) {
		Map<String, GroupManifest.Group> publishedGroups = publishedGroups(published);
		List<String> lines = new ArrayList<>();
		int groupCount = 0;
		for (var category : modpack.entrySet()) {
			lines.add(category.getKey() + ":");
			for (var group : category.getValue().entrySet()) {
				groupCount++;
				lines.add("  " + summaryLine(group.getKey(), group.getValue(), publishedGroups.get(group.getKey())));
			}
		}
		lines.add(0, "Modpack groups: " + modpack.size() + " categories, " + groupCount + " groups");
		return lines;
	}

	/** The detail view of one group, or empty when neither the config nor the published manifest knows the id. */
	public static Optional<List<String>> detail(Map<String, Map<String, ServerConfigJsons.GroupDeclaration>> modpack, String groupId, GroupManifest published) {
		Map.Entry<String, ServerConfigJsons.GroupDeclaration> declared = declaredGroup(modpack, groupId);
		GroupManifest.Group publishedGroup = publishedGroups(published).get(groupId);
		if (declared == null && publishedGroup == null) return Optional.empty();

		List<String> lines = new ArrayList<>();
		if (declared == null) {
			// Removed from the config but still served from the older generation; report the manifest side instead of "unknown".
			lines.add("Group " + groupId + " is published but missing from the server config");
			addField(lines, "Category", publishedGroup.category());
			addField(lines, "Display name", publishedGroup.displayName());
			addField(lines, "Description", publishedGroup.description());
			lines.add("Flags: " + flags(publishedGroup.required(), publishedGroup.defaultSelected()));
			addJoined(lines, "Platforms", platforms(publishedGroup));
			addJoined(lines, "Requires", publishedGroup.requires());
			addJoined(lines, "Breaks with", publishedGroup.breaksWith());
		} else {
			lines.add("Group " + groupId + " (category " + declared.getKey() + ")");
			ServerConfigJsons.GroupDeclaration declaration = declared.getValue();
			addField(lines, "Display name", declaration.displayName);
			addField(lines, "Description", declaration.description);
			lines.add("Flags: " + flags(declaration.required, declaration.defaultSelected));
			addJoined(lines, "Platforms", declaration.compatiblePlatforms);
			addJoined(lines, "Requires", declaration.requires);
			addJoined(lines, "Breaks with", declaration.breaksWith);
			addRules(lines, "From server", declaration.fromServer);
			addRules(lines, "Exclude", declaration.exclude);
			addRules(lines, "Editable", declaration.editable);
		}
		if (publishedGroup == null) lines.add("Published: (not published yet)");
		else filesSection(lines, publishedGroup);
		return Optional.of(lines);
	}

	private static String summaryLine(String groupId, ServerConfigJsons.GroupDeclaration declaration, GroupManifest.Group published) {
		StringBuilder line = new StringBuilder(groupId);
		if (!declaration.displayName.isBlank()) line.append(" - ").append(declaration.displayName);
		if (declaration.required) line.append(" [required]");
		if (declaration.defaultSelected) line.append(" [default]");
		if (!declaration.compatiblePlatforms.isEmpty()) line.append(" [").append(joined(declaration.compatiblePlatforms)).append("]");
		if (published == null) line.append(" (not published yet)");
		else line.append(" ").append(published.files().size()).append(" files, ").append(ByteFormat.formatSize(totalSize(published)));
		return line.toString();
	}

	private static void filesSection(List<String> lines, GroupManifest.Group group) {
		lines.add("Published files: " + group.files().size() + " files, " + ByteFormat.formatSize(totalSize(group)));
		Map<String, long[]> byType = new TreeMap<>();
		for (GroupManifest.GroupFile file : group.files().values()) {
			long[] countAndBytes = byType.computeIfAbsent(file.type(), ignored -> new long[2]);
			countAndBytes[0]++;
			countAndBytes[1] += file.size();
		}
		for (var type : byType.entrySet())
			lines.add("  " + type.getKey() + ": " + type.getValue()[0] + " files, " + ByteFormat.formatSize(type.getValue()[1]));
		lines.add("All files:");
		for (var file : new TreeMap<>(group.files()).entrySet())
			lines.add("  " + file.getKey() + " - " + ByteFormat.formatSize(file.getValue().size()));
	}

	private static Map.Entry<String, ServerConfigJsons.GroupDeclaration> declaredGroup(Map<String, Map<String, ServerConfigJsons.GroupDeclaration>> modpack, String groupId) {
		for (var category : modpack.entrySet()) {
			ServerConfigJsons.GroupDeclaration declaration = category.getValue().get(groupId);
			if (declaration != null) return Map.entry(category.getKey(), declaration);
		}
		return null;
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

	private static String flags(boolean required, boolean defaultSelected) {
		if (required && defaultSelected) return "required, default selected";
		if (required) return "required";
		if (defaultSelected) return "default selected";
		return "none";
	}

	private static void addField(List<String> lines, String label, String value) {
		if (value != null && !value.isBlank()) lines.add(label + ": " + value);
	}

	private static void addJoined(List<String> lines, String label, Set<String> values) {
		if (!values.isEmpty()) lines.add(label + ": " + joined(values));
	}

	/** Rule sets always print, an empty one as {@code (none)}: an empty rule is a deliberate posture, not missing data. */
	private static void addRules(List<String> lines, String label, Set<String> rules) {
		lines.add(label + ": " + (rules.isEmpty() ? "(none)" : joined(rules)));
	}

	private static String joined(Set<String> values) {
		return String.join(", ", new TreeSet<>(values));
	}
}
