package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.clientSelectionFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

/**
 * Remembers which groups the player picked per modpack, so downloads are filtered to that choice
 * and the "Optional Mods" screen reopens with the previous answer already ticked. The selection is
 * only ever changed by the player through that screen; it is never prompted automatically. This
 * class also turns a raw pick into a consistent set of groups by applying
 * required/requires/breaksWith.
 * The resolution half is static and free of I/O so it can be exercised without a game or a disk.
 */
public class ClientSelectionManager {

	private static final ClientSelectionManager INSTANCE = new ClientSelectionManager(clientSelectionFile);

	private final Path selectionFile;
	private final Jsons.ClientSelectionManagerFields selections;

	public static ClientSelectionManager getManager() {
		return INSTANCE;
	}

	ClientSelectionManager(Path selectionFile) {
		this.selectionFile = selectionFile;
		this.selections = ConfigTools.readOrCreate(selectionFile, Jsons.ClientSelectionManagerFields.class, Jsons.ClientSelectionManagerFields::new);
		if (this.selections.selections == null) this.selections.selections = new HashMap<>();
	}

	// =================================================================================
	// PERSISTENCE
	// =================================================================================

	public Optional<Jsons.ClientSelectionManagerFields.ModpackSelection> getSelection(String modpackId) {
		if (modpackId == null || modpackId.isBlank()) return Optional.empty();
		return Optional.ofNullable(selections.selections.get(modpackId));
	}

	/** Records the player's pick for a modpack, replacing any previous choice. */
	public void saveSelection(String modpackId, Set<String> selectedGroups) {
		if (modpackId == null || modpackId.isBlank()) {
			LOGGER.warn("Refusing to save a group selection without a modpack ID.");
			return;
		}

		selections.selections.put(modpackId, new Jsons.ClientSelectionManagerFields.ModpackSelection(new HashSet<>(selectedGroups)));
		save();
	}

	public void save() {
		try {
			ConfigTools.writeAtomic(selectionFile, selections);
		} catch (IOException e) {
			LOGGER.error("Failed to save client group selection", e);
		}
	}

	// =================================================================================
	// RESOLUTION (pure)
	// =================================================================================

	/** Pre-ticked state for a player who has not chosen yet: everything required or recommended. */
	public static Set<String> defaultSelection(Map<String, Jsons.ModpackContentFields.ModpackGroupFields> groups) {
		if (groups == null) return Set.of();
		Set<String> chosen = new LinkedHashSet<>();
		groups.forEach((id, group) -> {
			if (group != null && (group.required || group.recommended)) chosen.add(id);
		});
		return resolve(groups, chosen);
	}

	/**
	 * Turns a raw pick into a consistent set: forces required ones in, pulls in dependencies, and
	 * breaks up conflicts. Required groups always win a conflict; otherwise the group declared
	 * first does.
	 */
	public static Set<String> resolve(Map<String, Jsons.ModpackContentFields.ModpackGroupFields> groups, Set<String> chosen) {
		if (groups == null || groups.isEmpty()) return Set.of();

		Map<String, Jsons.ModpackContentFields.ModpackGroupFields> usable = new LinkedHashMap<>();
		groups.forEach((id, group) -> {
			if (group != null) usable.put(id, group);
		});

		Set<String> wanted = new LinkedHashSet<>();
		usable.forEach((id, group) -> {
			if (group.required || (chosen != null && chosen.contains(id))) wanted.add(id);
		});

		addRequiredDependencies(usable, wanted);

		// Dependencies a required group pulls in must win conflicts exactly like their required
		// dependent does, otherwise an optional group selected earlier can steal the conflict and
		// leave the required group's dependency missing (dropUnsatisfied then keeps the required
		// group anyway, producing an invalid selection).
		Set<String> mustHave = new LinkedHashSet<>();
		usable.forEach((id, group) -> {
			if (group.required) mustHave.add(id);
		});
		addRequiredDependencies(usable, mustHave);

		// Required (and their dependencies) first so they can never be the side that loses a conflict.
		List<String> byPriority = new ArrayList<>();
		usable.keySet().stream().filter(id -> wanted.contains(id) && mustHave.contains(id)).forEach(byPriority::add);
		usable.keySet().stream().filter(id -> wanted.contains(id) && !mustHave.contains(id)).forEach(byPriority::add);

		Set<String> kept = new LinkedHashSet<>();
		for (String id : byPriority) {
			String clash = kept.stream().filter(other -> conflicts(usable, other, id)).findFirst().orElse(null);
			if (clash == null) {
				kept.add(id);
			} else if (mustHave.contains(id)) {
				LOGGER.warn("Group {} is required (directly or as a dependency of a required group) but conflicts with {}; keeping both.", id, clash);
				kept.add(id);
			} else {
				LOGGER.info("Group {} conflicts with {}, leaving it out", id, clash);
			}
		}

		dropUnsatisfied(usable, kept);
		return kept;
	}

	private static void addRequiredDependencies(Map<String, Jsons.ModpackContentFields.ModpackGroupFields> usable, Set<String> wanted) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String id : new ArrayList<>(wanted)) {
				var group = usable.get(id);
				if (group == null || group.requires == null) continue;
				for (String dependency : group.requires) {
					if (dependency == null) continue;
					if (!usable.containsKey(dependency)) continue;
					if (wanted.add(dependency)) changed = true;
				}
			}
		}
	}

	/** A group whose dependency was never available, or lost a conflict, cannot stay selected. */
	private static void dropUnsatisfied(Map<String, Jsons.ModpackContentFields.ModpackGroupFields> usable, Set<String> kept) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String id : new ArrayList<>(kept)) {
				var group = usable.get(id);
				if (group == null || group.requires == null) continue;
				for (String dependency : group.requires) {
					if (dependency == null || dependency.isBlank()) continue;
					if (kept.contains(dependency)) continue;
					if (group.required) {
						LOGGER.warn("Required group {} depends on {}, which is unavailable.", id, dependency);
						continue;
					}
					kept.remove(id);
					LOGGER.info("Group {} needs {}, which is not selected; leaving it out", id, dependency);
					changed = true;
					break;
				}
			}
		}
	}

	public static boolean conflicts(Map<String, Jsons.ModpackContentFields.ModpackGroupFields> groups, String a, String b) {
		if (Objects.equals(a, b)) return false;
		return breaksWith(groups.get(a), b) || breaksWith(groups.get(b), a);
	}

	private static boolean breaksWith(Jsons.ModpackContentFields.ModpackGroupFields group, String other) {
		return group != null && group.breaksWith != null && group.breaksWith.contains(other);
	}

	/**
	 * Narrows a server manifest to the files the player's current selection keeps. Every part of the
	 * client that reasons about "what should be on disk" - the update check, the downloader, and the
	 * removal of files that are no longer wanted - must run on this filtered view rather than the
	 * server's full manifest, otherwise unselected files look perpetually missing and trigger an
	 * endless update loop. Returns the input untouched when there is nothing to filter.
	 */
	public static Jsons.ModpackContentFields filterToSelection(Jsons.ModpackContentFields content) {
		if (content == null || content.groups == null || content.groups.isEmpty() || content.list == null) return content;

		Set<String> chosen = getManager().getSelection(content.modpackId).map(selection -> selection.selectedGroups)
				.orElseGet(() -> defaultSelection(content.groups));
		Set<String> resolved = resolve(content.groups, chosen);
		Set<String> allowedFiles = selectedFiles(content, resolved);

		if (allowedFiles.size() == content.list.size()) return content;
		LOGGER.info("Group selection covers {} of {} modpack files (groups: {})", allowedFiles.size(), content.list.size(), resolved);

		Set<Jsons.ModpackContentFields.ModpackContentItem> keptItems = new HashSet<>();
		content.list.forEach(item -> {
			if (allowedFiles.contains(item.file)) keptItems.add(item);
		});

		var filtered = new Jsons.ModpackContentFields(keptItems);
		filtered.modpackId = content.modpackId;
		filtered.modpackName = content.modpackName;
		filtered.automodpackVersion = content.automodpackVersion;
		filtered.loader = content.loader;
		filtered.loaderVersion = content.loaderVersion;
		filtered.mcVersion = content.mcVersion;
		filtered.nonModpackFilesToDelete = content.nonModpackFilesToDelete;
		filtered.groups = content.groups;
		return filtered;
	}

	/**
	 * Relative paths the given groups cover. Files outside every group stay included, so a client
	 * talking to a server that never declared groups still receives the whole modpack.
	 */
	public static Set<String> selectedFiles(Jsons.ModpackContentFields content, Set<String> selectedGroups) {
		if (content == null || content.list == null) return Set.of();
		if (content.groups == null || content.groups.isEmpty()) {
			Set<String> everything = new HashSet<>();
			content.list.forEach(item -> everything.add(item.file));
			return everything;
		}

		Set<String> grouped = new HashSet<>();
		Set<String> allowed = new HashSet<>();
		content.groups.forEach((id, group) -> {
			if (group == null || group.files == null) return;
			grouped.addAll(group.files);
			if (selectedGroups != null && selectedGroups.contains(id)) allowed.addAll(group.files);
		});

		content.list.forEach(item -> {
			if (!grouped.contains(item.file)) allowed.add(item.file);
		});

		return allowed;
	}

	/**
	 * Filenames declared for resource pack auto-apply (GroupDeclaration.autoApplyResourcePacks) by
	 * whichever of {@code content}'s groups actually have a matching resourcepack item present.
	 * {@code content} is expected to already be selection-filtered (see filterToSelection), so a
	 * file only shows up here if its group was selected - this is a pure lookup, not a
	 * re-resolution of group selection.
	 */
	public static Set<String> autoApplyResourcePackFiles(Jsons.ModpackContentFields content) {
		if (content == null || content.groups == null || content.groups.isEmpty() || content.list == null) return Set.of();

		Set<String> presentResourcePackFiles = new HashSet<>();
		content.list.forEach(item -> {
			if ("resourcepack".equals(item.type)) presentResourcePackFiles.add(item.file);
		});
		if (presentResourcePackFiles.isEmpty()) return Set.of();

		Set<String> fileNames = new LinkedHashSet<>();
		content.groups.values().forEach(group -> {
			if (group == null || group.autoApplyResourcePacks == null || group.autoApplyResourcePacks.isEmpty() || group.files == null) return;
			for (String file : group.files) {
				if (!presentResourcePackFiles.contains(file)) continue;
				String fileName = fileName(file);
				if (group.autoApplyResourcePacks.contains(fileName)) fileNames.add(fileName);
			}
		});
		return fileNames;
	}

	private static String fileName(String file) {
		int lastSlash = file.lastIndexOf('/');
		return lastSlash < 0 ? file : file.substring(lastSlash + 1);
	}
}
