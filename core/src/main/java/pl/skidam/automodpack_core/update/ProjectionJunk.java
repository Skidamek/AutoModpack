package pl.skidam.automodpack_core.update;

import java.util.Locale;
import java.util.Set;

/**
 * Names an operating system or file browser drops into any directory it shows: never pack content unless the pack ships
 * that exact path. They are not drift the planner reports, never part of a fallback, and their removal is best effort,
 * so a thumbnail cache Explorer holds open cannot keep an update owed.
 */
final class ProjectionJunk {
	private static final Set<String> NAMES = Set.of(".ds_store", "thumbs.db", "ehthumbs.db", "ehthumbs_vista.db", "desktop.ini", ".directory");

	private ProjectionJunk() {}

	/** Whether the last element of {@code relativePath} is such a name: Finder, Explorer, KDE metadata, AppleDouble forks, NFS silly renames. */
	static boolean isJunk(String relativePath) {
		String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
		return NAMES.contains(name.toLowerCase(Locale.ROOT)) || name.startsWith("._") || name.startsWith(".nfs");
	}
}
