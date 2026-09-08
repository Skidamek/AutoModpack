package pl.skidam.automodpack_core.modpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

public class GroupManager {

	private static final Pattern INVALID_CHARS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");
	private static final Set<String> RESERVED_NAMES = Set.of(
			"CON", "PRN", "AUX", "NUL",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
			"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
	private static final int MAX_NAME_LENGTH = 100;

	private GroupManager() {}

	// Group ids are used directly as directory names (see groupDirectory), so they're restricted to
	// what both Windows and Unix accept as a valid, unambiguous filename.
	public static void validateName(String name) {
		if (name == null || name.isBlank()) throw new IllegalArgumentException("Group name cannot be empty");
		if (name.length() > MAX_NAME_LENGTH) throw new IllegalArgumentException("Group name cannot be longer than " + MAX_NAME_LENGTH + " characters");
		if (!name.equals(name.strip())) throw new IllegalArgumentException("Group name cannot have leading or trailing whitespace");
		if (name.equals(".") || name.equals("..")) throw new IllegalArgumentException("Group name cannot be \".\" or \"..\"");
		if (name.endsWith(".")) throw new IllegalArgumentException("Group name cannot end with a dot");
		if (INVALID_CHARS.matcher(name).find()) throw new IllegalArgumentException("Group name cannot contain any of: < > : \" / \\ | ? * or control characters");

		String baseName = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
		if (RESERVED_NAMES.contains(baseName.toUpperCase(Locale.ROOT))) {
			throw new IllegalArgumentException("\"" + name + "\" is a reserved name and cannot be used as a group name");
		}
	}

	public static Path groupDirectory(String groupId) {
		return Constants.hostModpackDir.resolve(groupId);
	}

	public static void createGroupFolders(Path groupDirectory) throws IOException {
		Files.createDirectories(groupDirectory.resolve("config"));
		Files.createDirectories(groupDirectory.resolve("mods"));
		Files.createDirectories(groupDirectory.resolve("resourcepacks"));
		Files.createDirectories(groupDirectory.resolve("shaderpacks"));
	}

	public static void deleteGroupFolders(Path groupDirectory) throws IOException {
		if (!Files.exists(groupDirectory)) return;
		try (Stream<Path> paths = Files.walk(groupDirectory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	public record GroupCounts(int mods, int resourcepacks, int shaders) {
		public static final GroupCounts EMPTY = new GroupCounts(0, 0, 0);
	}

	// Per-type counts only exist in the generated manifest (Jsons.GroupDeclaration itself has no file
	// lists), so this returns EMPTY until the modpack has been generated at least once.
	public static GroupCounts countGroup(String groupId) {
		Jsons.ModpackContentFields manifest = ModpackContentTools.read(Constants.hostModpackContentFile);
		Jsons.ModpackContentFields.ModpackGroupFields group = manifest == null ? null : manifest.groups.get(groupId);
		if (group == null) return GroupCounts.EMPTY;

		int mods = 0, resourcepacks = 0, shaders = 0;
		for (Jsons.ModpackContentFields.ModpackContentItem item : manifest.list) {
			if (!group.files.contains(item.file)) continue;
			switch (item.type) {
				case "mod" -> mods++;
				case "resourcepack" -> resourcepacks++;
				case "shader" -> shaders++;
				default -> {
				}
			}
		}
		return new GroupCounts(mods, resourcepacks, shaders);
	}

	public static List<String> listGroupFiles(String groupId, String type) {
		Jsons.ModpackContentFields manifest = ModpackContentTools.read(Constants.hostModpackContentFile);
		Jsons.ModpackContentFields.ModpackGroupFields group = manifest == null ? null : manifest.groups.get(groupId);
		if (group == null) return List.of();

		List<String> files = new ArrayList<>();
		for (Jsons.ModpackContentFields.ModpackContentItem item : manifest.list) {
			if (group.files.contains(item.file) && item.type.equals(type)) {
				files.add(item.file);
			}
		}
		files.sort(String.CASE_INSENSITIVE_ORDER);
		return files;
	}

	public static boolean isRequired(String groupId) {
		Jsons.GroupDeclaration declaration = Constants.serverConfig.groups.get(groupId);
		return declaration != null && declaration.required;
	}
}
