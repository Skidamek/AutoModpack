package pl.skidam.automodpack.client.ui.screen;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;

/** Shared copy and catalogue helpers for matched / unverified confirm screens. */
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

	static String packSummary(SelectedModpackTarget target) {
		long bytes = target.flatTarget().list.stream().mapToLong(item -> Long.parseLong(item.size)).sum();
		return VersionedText.translatable("automodpack.confirm.packSummary", UiFormat.plural(target.selection().selectedGroups().size(), "automodpack.confirm.groupCount").getString(),
				UiFormat.plural(target.flatTarget().list.size(), "automodpack.confirm.fileCount").getString(), UiFormat.formatSize(bytes)).getString();
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

	static ChangeSet catalogue(ModpackUpdater updater) {
		return updater.reviewCatalogue();
	}

	static Map<String, String> featureNames(GroupManifest manifest) {
		Map<String, String> names = new TreeMap<>();
		manifest.groups().forEach((id, group) -> names.put(id, group.displayName().isBlank() ? id : group.displayName()));
		return names;
	}

	static Component leftoverLabel(boolean keep, int count) {
		return VersionedText.translatable(keep ? "automodpack.firstConnect.leftoverKeep" : "automodpack.firstConnect.leftoverArchive", count);
	}

	static MutableComponent ackLabel() {
		return VersionedText.translatable("automodpack.confirm.ack");
	}
}
