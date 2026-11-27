package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.screen.SourceCounts;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.JarUtils;

/** Shared copy and catalogue helpers for the pack confirm screen. */
final class PackConfirmCopy {
	private PackConfirmCopy() {}

	static String computerRisk() {
		return VersionedText.str("automodpack.confirm.computerRisk");
	}

	static String sharedCommands() {
		return VersionedText.str("automodpack.confirm.commands");
	}

	static String matchedHonesty() {
		return VersionedText.str("automodpack.confirm.matchedHonesty");
	}

	static String unverifiedExplain() {
		return VersionedText.str("automodpack.confirm.unverifiedExplain");
	}

	/** Why jars still read as unverified: a platform that never answered is not the same as a platform that answered with nothing. */
	static String unreachableNotice(Set<DownloadSource.Provider> unreachable) {
		if (unreachable == null || unreachable.isEmpty()) return "";
		boolean modrinth = unreachable.contains(DownloadSource.Provider.MODRINTH);
		boolean curseforge = unreachable.contains(DownloadSource.Provider.CURSEFORGE);
		if (modrinth && curseforge) return VersionedText.str("automodpack.confirm.unreachablePlatforms", "Modrinth and CurseForge");
		if (modrinth) return VersionedText.str("automodpack.confirm.unreachablePlatforms", "Modrinth");
		if (curseforge) return VersionedText.str("automodpack.confirm.unreachablePlatforms", "CurseForge");
		return "";
	}

	static String intro(String origin) {
		return VersionedText.str("automodpack.confirm.intro", origin);
	}

	static String displayOrigin(String originFull) {
		if (originFull == null || originFull.isBlank()) return "";
		return originFull.endsWith(":25565") ? originFull.substring(0, originFull.length() - 6) : originFull;
	}

	/** The download cost of the selected target: the sum of every selected file's size. */
	static long selectedBytes(SelectedModpackTarget target) {
		if (target.flatTarget().list == null) return 0;
		long bytes = 0;
		for (var item : target.flatTarget().list) bytes += item.size;
		return bytes;
	}

	static String selectedSummary(SelectedModpackTarget target) {
		return VersionedText.str("automodpack.firstConnect.selectedSummary", target.selection().selectedGroups().size(), target.flatTarget().list.size(), UiFormat.formatSize(selectedBytes(target)));
	}

	/** The selected target's size for one file path, or 0 when the path is not part of the target. */
	static long selectedJarSize(SelectedModpackTarget target, String path) {
		if (target.flatTarget().list == null) return 0;
		for (var item : target.flatTarget().list) if (path.equals(item.file)) return Math.max(0, item.size);
		return 0;
	}

	static String requestedGroups(SelectedModpackTarget target) {
		if (target.selection().intent().enabled().isEmpty()) return "";
		return VersionedText.str("automodpack.firstConnect.requestedGroups", groupNames(target.manifest(), target.selection().intent().enabled()));
	}

	static String includedGroups(SelectedModpackTarget target) {
		if (target.selection().selectedGroups().isEmpty()) return "";
		return VersionedText.str("automodpack.firstConnect.includedGroups", groupNames(target.manifest(), target.selection().selectedGroups()));
	}

	static String requestedUnavailableGroups(SelectedModpackTarget target) {
		if (target.selection().requestedUnavailableGroups().isEmpty()) return "";
		return VersionedText.str("automodpack.firstConnect.requestedUnavailable", groupNames(target.manifest(), target.selection().requestedUnavailableGroups()));
	}

	static String staleRequestedGroups(SelectedModpackTarget target) {
		if (target.selection().staleGroups().isEmpty()) return "";
		return VersionedText.str("automodpack.firstConnect.unavailableOldChoices", groupNames(target.manifest(), target.selection().staleGroups()));
	}

	static String existingMods(boolean keep, int count) {
		if (count <= 0) return "";
		return VersionedText.str(keep ? "automodpack.firstConnect.existingModsKeep" : "automodpack.firstConnect.existingModsArchive", count);
	}

	private static String groupNames(GroupManifest manifest, Iterable<String> ids) {
		List<String> names = new ArrayList<>();
		for (String id : ids) names.add(manifest.displayName(id));
		return String.join(", ", names);
	}

	static MutableComponent customizeLabel() {
		return VersionedText.text("automodpack.confirm.customize");
	}

	static String unverifiedCount(int unverified, int jars) {
		return VersionedText.str("automodpack.confirm.unverifiedCount", unverified, jars);
	}

	/**
	 * Platform match split for the confirm stats. Empty when neither platform matched, so the line hides instead of
	 * reading zeros. A zero on one platform is omitted; unverified jars have their own red count below this.
	 */
	static String sourceCounts(SourceCounts counts) {
		if (counts == null) return "";
		boolean modrinth = counts.modrinth() > 0;
		boolean curseforge = counts.curseforge() > 0;
		if (!modrinth && !curseforge) return "";
		if (modrinth && curseforge)
			return VersionedText.str("automodpack.confirm.matchedModrinth", counts.modrinth()) + "  |  "
					+ VersionedText.str("automodpack.confirm.matchedCurseforgeJoined", counts.curseforge());
		if (modrinth) return VersionedText.str("automodpack.confirm.matchedModrinth", counts.modrinth());
		return VersionedText.str("automodpack.confirm.matchedCurseforge", counts.curseforge());
	}

	static int selectedJarCount(SelectedModpackTarget target) {
		if (target.flatTarget().list == null) return 0;
		int count = 0;
		for (var item : target.flatTarget().list) {
			if (JarUtils.hasJarExtension(item.file)) count++;
		}
		return count;
	}

	static Map<String, String> groupNames(GroupManifest manifest) {
		return InstalledModpackController.groupNames(manifest);
	}

	static boolean canCustomize(GroupManifest manifest) {
		return manifest.hasChoosableGroups();
	}

	static Component leftoverLabel(int count) {
		return VersionedText.text("automodpack.confirm.keepExistingMods", count);
	}

	static MutableComponent ackLabel() {
		return VersionedText.text("automodpack.confirm.ack");
	}

	static MutableComponent launchWritesLabel() {
		return VersionedText.text("automodpack.confirm.launchWrites");
	}
}
