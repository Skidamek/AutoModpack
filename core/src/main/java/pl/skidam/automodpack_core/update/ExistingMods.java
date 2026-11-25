package pl.skidam.automodpack_core.update;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;

/**
 * What the plan does with the mod files the player already had when a pack takes over their folder: keep them as they
 * are, or move the named paths to the instance timeline. One value because the two answers are exclusive; keep also
 * stops the pack's own tombstones from being applied, so a plan can never archive a file it was told to keep.
 *
 * <p>
 * The answer names paths, not bytes: the plan archives whatever the path holds when it runs, and the preservation
 * records those bytes. A mods folder the player touched while the review was open therefore still gets a well-defined
 * answer instead of refusing the whole update.
 */
public record ExistingMods(boolean keepExisting, Set<String> archived) {
	/** No player answer yet: nothing is archived and the pack's tombstones still apply. */
	public static final ExistingMods NONE = new ExistingMods(false, Set.of());

	/** The player keeps everything already in the folder. */
	public static ExistingMods keep() {
		return new ExistingMods(true, Set.of());
	}

	/** The player moves exactly these paths to the instance timeline. */
	public static ExistingMods archive(Collection<String> paths) {
		return new ExistingMods(false, new TreeSet<>(paths));
	}

	public ExistingMods {
		Objects.requireNonNull(archived, "archived paths");
		TreeSet<String> normalized = new TreeSet<>();
		for (String path : archived) normalized.add(LogicalPath.normalize(path));
		archived = Collections.unmodifiableSortedSet(normalized);
		if (keepExisting && !archived.isEmpty()) throw new IllegalArgumentException("Existing mods are either kept or archived, never both");
	}
}
