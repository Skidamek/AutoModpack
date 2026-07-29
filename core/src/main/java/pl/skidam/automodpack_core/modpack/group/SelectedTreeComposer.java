package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

import pl.skidam.automodpack_core.config.Jsons;

public final class SelectedTreeComposer {
	private SelectedTreeComposer() {}

	public static Jsons.ModpackContentFields compose(GroupManifest manifest, ResolvedSelection selection) {
		Map<String, GroupManifest.GroupFile> files = new TreeMap<>();
		for (String groupId : selection.selectedGroups()) {
			GroupManifest.Group group = manifest.groups().get(groupId);
			if (group == null) throw new IllegalArgumentException("Selected group is absent from the catalogue: " + groupId);
			for (var entry : group.files().entrySet()) {
				GroupManifest.GroupFile previous = files.putIfAbsent(entry.getKey(), entry.getValue());
				if (previous != null && !previous.sameEffectiveState(entry.getValue()))
					throw new IllegalArgumentException("Selected groups produce conflicting path: " + entry.getKey());
			}
		}

		Set<Jsons.ModpackContentFields.ModpackContentItem> selectedFiles = new LinkedHashSet<>();
		for (var entry : files.entrySet()) {
			GroupManifest.GroupFile file = entry.getValue();
			selectedFiles.add(new Jsons.ModpackContentFields.ModpackContentItem(entry.getKey(), String.valueOf(file.size()), file.type(), file.editable(),
					file.overwriteEditable(), file.forceCopy(), file.sha1(), file.murmur()));
		}

		Jsons.ModpackContentFields target = new Jsons.ModpackContentFields(selectedFiles);
		target.modpackId = manifest.modpackId();
		target.modpackName = manifest.modpackName();
		target.automodpackVersion = manifest.automodpackVersion();
		target.loader = manifest.loader();
		target.loaderVersion = manifest.loaderVersion();
		target.mcVersion = manifest.mcVersion();
		target.selectedGroups = new LinkedHashSet<>(selection.selectedGroups());
		Set<Jsons.ModpackContentFields.FileToDelete> deletions = new LinkedHashSet<>();
		for (GroupManifest.DeletionRequest deletion : manifest.nonModpackFilesToDelete())
			deletions.add(new Jsons.ModpackContentFields.FileToDelete(deletion.file(), deletion.sha1(), deletion.timestamp()));
		target.nonModpackFilesToDelete = deletions;
		return target;
	}
}
