package pl.skidam.automodpack_core.modpack.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.config.ServerConfigStore;
import pl.skidam.automodpack_core.config.WireJsons;
import pl.skidam.automodpack_core.modpack.PackAbsence;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedTreeComposer;

/** Builds the pre-serialized host advertisement served by GET /advertise. */
public final class HostAdvertisement {
	private static final Logger LOGGER = LogManager.getLogger(HostAdvertisement.class);

	private HostAdvertisement() {}

	public static byte[] buildBytes(GenerationHosting hosting, PackAbsence absence) {
		WireJsons.AdvertiseFields ad = build(hosting, absence);
		return ad.toJson().getBytes(StandardCharsets.UTF_8);
	}

	public static WireJsons.AdvertiseFields build(GenerationHosting hosting, PackAbsence absence) {
		ServerConfigJsons.ServerConfigFieldsV3 config = ServerConfigStore.installed();
		Environment env = Environment.installed();
		WireJsons.AdvertiseFields ad = new WireJsons.AdvertiseFields();
		ad.v = WireJsons.WIRE_CONTRACT_VERSION;
		ad.amVersion = env == null ? null : env.modVersion();
		ad.require = config != null && config.requireModpack;

		Path headPath = hosting == null ? null : hosting.get(GenerationHosting.HEAD_DOCUMENT_KEY);
		if (headPath != null && Files.isRegularFile(headPath)) {
			try {
				GenerationJsons.HeadDocumentFields head = ConfigTools.parse(Files.readString(headPath, StandardCharsets.UTF_8), GenerationJsons.HeadDocumentFields.class);
				if (head != null && head.policy != null) {
					ad.route = config != null && config.modpackHost ? WireJsons.RouteFields.fromServerConfig(config) : null;
					populateFromHead(ad, head);
					return ad;
				}
			} catch (Exception e) {
				LOGGER.warn("Failed to read head document while building host advertisement", e);
			}
		}

		ad.pack = null;
		ad.route = null;
		ad.absence = absence != null ? absence.kind() : PackAbsence.Kind.NEVER_PUBLISHED;
		ad.target = currentServerTarget();
		return ad;
	}

	private static void populateFromHead(WireJsons.AdvertiseFields ad, GenerationJsons.HeadDocumentFields head) {
		ModpackJsons.CompleteModpackContentFields policy = head.policy;
		int fileCount = 0;
		long totalBytes = 0L;
		try {
			GroupManifest manifest = GroupManifestValidator.validate(policy);
			ResolvedSelection defaults = GroupSelectionResolver.resolveDefault(manifest, null);
			ModpackJsons.ModpackContentFields composed = SelectedTreeComposer.compose(manifest, defaults);
			fileCount = composed.list.size();
			for (var item : composed.list) {
				if (item != null) totalBytes += Math.max(0L, item.size);
			}
		} catch (RuntimeException e) {
			Map<String, Long> uniqueFiles = new HashMap<>();
			if (policy.groups != null) {
				for (var group : policy.groups.values()) {
					if (group == null || group.files == null) continue;
					if (group.selection == null || !group.selection.arrivesSelected()) continue;
					for (var entry : group.files.entrySet()) {
						String path = entry.getKey();
						var file = entry.getValue();
						if (path == null || file == null) continue;
						long size = 0L;
						if (file.size != null && !file.size.isBlank()) {
							try {
								size = Long.parseLong(file.size);
							} catch (NumberFormatException ignored) {
							}
						}
						uniqueFiles.putIfAbsent(path, Math.max(0L, size));
					}
				}
			}
			fileCount = uniqueFiles.size();
			for (long size : uniqueFiles.values()) totalBytes += size;
		}

		ad.pack = new WireJsons.PackSummaryFields(policy.modpackId, policy.modpackName, fileCount, totalBytes);
		ad.absence = null;
		ad.generation = head.contentToken;
		boolean hasTarget = !isBlank(policy.mcVersion) || !isBlank(policy.loader) || !isBlank(policy.loaderVersion);
		ad.target = hasTarget ? new WireJsons.TargetFields(nullIfBlank(policy.mcVersion), nullIfBlank(policy.loader), nullIfBlank(policy.loaderVersion)) : null;
	}

	private static WireJsons.TargetFields currentServerTarget() {
		Environment env = Environment.installed();
		if (env == null) return null;
		String mc = nullIfBlank(env.mcVersion());
		String loader = nullIfBlank(env.loader());
		String loaderVersion = nullIfBlank(env.loaderVersion());
		if (mc == null && loader == null && loaderVersion == null) return null;
		return new WireJsons.TargetFields(mc, loader, loaderVersion);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String nullIfBlank(String value) {
		return isBlank(value) ? null : value;
	}
}
