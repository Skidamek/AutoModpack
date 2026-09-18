package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.screen.SourceCounts;

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
		if (target.selection().intent().requestedGroups().isEmpty()) return "";
		return VersionedText.str("automodpack.firstConnect.requestedGroups", groupNames(target.manifest(), target.selection().intent().requestedGroups()));
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
		if (target.selection().staleRequestedGroups().isEmpty()) return "";
		return VersionedText.str("automodpack.firstConnect.unavailableOldChoices", groupNames(target.manifest(), target.selection().staleRequestedGroups()));
	}

	static String existingMods(boolean keep, int count) {
		if (count <= 0) return "";
		return VersionedText.str(keep ? "automodpack.firstConnect.existingModsKeep" : "automodpack.firstConnect.existingModsArchive", count);
	}

	private static String groupNames(GroupManifest manifest, Iterable<String> ids) {
		List<String> names = new ArrayList<>();
		for (String id : ids) {
			GroupManifest.Group group = manifest.groups().get(id);
			names.add(group == null || group.displayName().isBlank() ? id : group.displayName());
		}
		return String.join(", ", names);
	}

	static MutableComponent customizeLabel() {
		return VersionedText.text("automodpack.confirm.customize");
	}

	static String unverifiedCount(int unverified, int jars) {
		return VersionedText.str("automodpack.confirm.unverifiedCount", unverified, jars);
	}

	/** The per-source breakdown line; empty when the target has no jars, so the stat hides instead of reading zeros. */
	static String sourceCounts(SourceCounts counts) {
		if (counts == null || (counts.modrinth() == 0 && counts.curseforge() == 0 && counts.serverOnly() == 0)) return "";
		return VersionedText.str("automodpack.confirm.sourceCounts", counts.modrinth(), counts.curseforge(), counts.serverOnly());
	}

	static int selectedJarCount(SelectedModpackTarget target) {
		if (target.flatTarget().list == null) return 0;
		int count = 0;
		for (var item : target.flatTarget().list) {
			if (item.file != null && item.file.toLowerCase(Locale.ROOT).endsWith(".jar")) count++;
		}
		return count;
	}

	static Map<String, String> groupNames(GroupManifest manifest) {
		return InstalledModpackController.groupNames(manifest);
	}

	static boolean canCustomize(GroupManifest manifest) {
		return manifest.groups().values().stream().anyMatch(group -> !group.required());
	}

	static Component leftoverLabel(int count) {
		return VersionedText.text("automodpack.confirm.keepExistingMods", count);
	}

	static MutableComponent ackLabel() {
		return VersionedText.text("automodpack.confirm.ack");
	}
}
