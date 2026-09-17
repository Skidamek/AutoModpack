package pl.skidam.automodpack_core.update;

import java.util.List;
import java.util.Map;

import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * One pack's pre-install state: what existed at each game-directory path before the pack first touched it. Derived
 * from the pack's state-history captures, where the first capture per path wins - a later capture names bytes a
 * previous entry already tracks. The removal and cleanup flows restore these bytes; the journal pins keep them safe
 * from collection.
 */
public record PreInstallState(String modpackId, Map<String, Entry> entriesByPath) {
	public PreInstallState {
		modpackId = ModpackId.requireValid(modpackId);
		entriesByPath = Map.copyOf(entriesByPath);
	}

	public List<Entry> entries() {
		return List.copyOf(entriesByPath.values());
	}

	public record Entry(String logicalPath, String objectHash, long size, boolean absent) {
		public Entry {
			logicalPath = LogicalPath.requireCanonical(logicalPath);
			if (absent) {
				if (objectHash != null || size != 0) throw new IllegalArgumentException("An absent pre-install entry cannot carry content: " + logicalPath);
			} else {
				if (!HashUtils.isCanonicalSha1(objectHash)) throw new IllegalArgumentException("Invalid pre-install hash for " + logicalPath);
				if (size < 0) throw new IllegalArgumentException("Negative pre-install size for " + logicalPath);
			}
		}
	}
}
