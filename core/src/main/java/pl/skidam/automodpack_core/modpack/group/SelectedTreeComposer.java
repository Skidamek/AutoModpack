package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;

public final class SelectedTreeComposer {
	private SelectedTreeComposer() {}

	public static ModpackJsons.ModpackContentFields compose(GroupManifest manifest, ResolvedSelection selection) {
		return compose(manifest, selection, null);
	}

	public static ModpackJsons.ModpackContentFields compose(GroupManifest manifest, ResolvedSelection selection, PackTarget packTarget) {
		return compose(manifest, selection.selectedGroups(), packTarget, true);
	}

	public static ModpackJsons.ModpackContentFields composeAll(GroupManifest manifest, PackTarget packTarget) {
		return compose(manifest, new TreeSet<>(manifest.groups().keySet()), packTarget, false);
	}

	private static ModpackJsons.ModpackContentFields compose(GroupManifest manifest, Collection<String> groupIds, PackTarget packTarget, boolean resolvePaths) {
		Map<String, GroupManifest.GroupFile> files = new TreeMap<>();
		for (String groupId : groupIds) {
			GroupManifest.Group group = manifest.groups().get(groupId);
			if (group == null) throw new IllegalArgumentException("Selected group is absent from the catalogue: " + groupId);
			for (var entry : group.files().entrySet()) {
				if (!resolvePaths) break;
				GroupManifest.GroupFile previous = files.putIfAbsent(entry.getKey(), entry.getValue());
				if (previous != null && !previous.sameEffectiveState(entry.getValue()))
					throw new IllegalArgumentException("Selected groups produce conflicting path: " + entry.getKey());
			}
		}
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> selectedFiles = resolvePaths ? resolvedItems(files) : allItems(manifest, groupIds);

		ModpackJsons.ModpackContentFields target = new ModpackJsons.ModpackContentFields(selectedFiles);
		target.modpackId = manifest.modpackId();
		target.modpackName = manifest.modpackName();
		target.automodpackVersion = manifest.automodpackVersion();
		target.loader = manifest.loader();
		target.loaderVersion = manifest.loaderVersion();
		target.mcVersion = manifest.mcVersion();
		target.selectedGroups = new LinkedHashSet<>(groupIds);
		if (packTarget != null) {
			if (!manifest.modpackId().equals(packTarget.modpackId()))
				throw new IllegalArgumentException("Selected target generation identity does not match catalogue modpack ID");
			target.contentToken = packTarget.contentToken();
			target.policySha1 = packTarget.policySha1();
			target.ownershipLedger.digest = packTarget.ledgerDigest();
		}
		return target;
	}

	private static Set<ModpackJsons.ModpackContentFields.ModpackContentItem> resolvedItems(Map<String, GroupManifest.GroupFile> files) {
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> items = new LinkedHashSet<>();
		for (var entry : files.entrySet()) {
			GroupManifest.GroupFile file = entry.getValue();
			items.add(new ModpackJsons.ModpackContentFields.ModpackContentItem(entry.getKey(), String.valueOf(file.size()), file.type(), file.editable(), file.sha1(), file.murmur()));
		}
		return items;
	}

	private static Set<ModpackJsons.ModpackContentFields.ModpackContentItem> allItems(GroupManifest manifest, Collection<String> groupIds) {
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> items = new LinkedHashSet<>();
		for (String groupId : groupIds) for (var entry : manifest.groups().get(groupId).files().entrySet()) {
			GroupManifest.GroupFile file = entry.getValue();
			items.add(new ModpackJsons.ModpackContentFields.ModpackContentItem(entry.getKey(), String.valueOf(file.size()), file.type(), file.editable(), file.sha1(), file.murmur()));
		}
		return items;
	}
}
