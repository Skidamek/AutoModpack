package pl.skidam.automodpack_core.modpack.group;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * What a set of selected groups installs: the bytes those paths need. A path two groups share is counted once; two paths
 * holding the same content need one download, so their bytes count once too.
 *
 * <p>
 * The merge is deliberately lenient: a partial resolution from a failed selection still has to render. The composer,
 * which actually composes a selection, is the strict one and refuses a missing group or conflicting state.
 * </p>
 */
public record GroupSelectionSummary(long byteCount) {
	public GroupSelectionSummary {
		if (byteCount < 0) throw new IllegalArgumentException("A selection summary cannot be negative");
	}

	public static GroupSelectionSummary of(GroupManifest manifest, Collection<String> selectedGroups) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(selectedGroups, "selected groups");
		Map<String, GroupManifest.GroupFile> files = new TreeMap<>();
		for (String groupId : selectedGroups) {
			GroupManifest.Group group = manifest.groups().get(groupId);
			if (group == null) continue;
			for (var entry : group.files().entrySet()) files.putIfAbsent(entry.getKey(), entry.getValue());
		}
		Map<String, Long> bytesByContent = new LinkedHashMap<>();
		for (GroupManifest.GroupFile file : files.values()) bytesByContent.putIfAbsent(HashUtils.normalizeSha1(file.sha1()), file.size());
		long bytes = 0;
		for (long size : bytesByContent.values()) bytes = Math.addExact(bytes, size);
		return new GroupSelectionSummary(bytes);
	}
}
