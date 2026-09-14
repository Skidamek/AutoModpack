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

/** Shared copy and catalogue helpers for the pack confirm screen. */
final class PackConfirmCopy {
	private PackConfirmCopy() {}

	static String computerRisk() {
		return VersionedText.translatable("automodpack.confirm.computerRisk").getString();
	}

	static String sharedCommands() {
		return VersionedText.translatable("automodpack.confirm.commands").getString();
	}

	static String matchedHonesty() {
		return VersionedText.translatable("automodpack.confirm.matchedHonesty").getString();
	}

	static String unverifiedExplain() {
		return VersionedText.translatable("automodpack.confirm.unverifiedExplain").getString();
	}

	static String intro(String origin) {
		return VersionedText.translatable("automodpack.confirm.intro", origin).getString();
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
		return VersionedText.translatable("automodpack.firstConnect.selectedSummary", target.selection().selectedGroups().size(), target.flatTarget().list.size(), UiFormat.formatSize(selectedBytes(target))).getString();
	}

	/** The selected target's size for one file path, or 0 when the path is not part of the target. */
	static long selectedJarSize(SelectedModpackTarget target, String path) {
		if (target.flatTarget().list == null) return 0;
		for (var item : target.flatTarget().list) if (path.equals(item.file)) return Math.max(0, item.size);
		return 0;
	}

	static String requestedGroups(SelectedModpackTarget target) {
		if (target.selection().intent().requestedGroups().isEmpty()) return "";
		return VersionedText.translatable("automodpack.firstConnect.requestedGroups", groupNames(target.manifest(), target.selection().intent().requestedGroups())).getString();
	}

	static String includedGroups(SelectedModpackTarget target) {
		if (target.selection().selectedGroups().isEmpty()) return "";
		return VersionedText.translatable("automodpack.firstConnect.includedGroups", groupNames(target.manifest(), target.selection().selectedGroups())).getString();
	}

	static String requestedUnavailableGroups(SelectedModpackTarget target) {
		if (target.selection().requestedUnavailableGroups().isEmpty()) return "";
		return VersionedText.translatable("automodpack.firstConnect.requestedUnavailable", groupNames(target.manifest(), target.selection().requestedUnavailableGroups())).getString();
	}

	static String staleRequestedGroups(SelectedModpackTarget target) {
		if (target.selection().staleRequestedGroups().isEmpty()) return "";
		return VersionedText.translatable("automodpack.firstConnect.unavailableOldChoices", groupNames(target.manifest(), target.selection().staleRequestedGroups())).getString();
	}

	static String existingMods(boolean keep, int count) {
		if (count <= 0) return "";
		return VersionedText.translatable(keep ? "automodpack.firstConnect.existingModsKeep" : "automodpack.firstConnect.existingModsArchive", count).getString();
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
		return VersionedText.translatable("automodpack.confirm.customize");
	}

	static String unverifiedCount(int unverified, int jars) {
		return VersionedText.translatable("automodpack.confirm.unverifiedCount", unverified, jars).getString();
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
		return VersionedText.translatable("automodpack.confirm.keepExistingMods", count);
	}

	static MutableComponent ackLabel() {
		return VersionedText.translatable("automodpack.confirm.ack");
	}
}
