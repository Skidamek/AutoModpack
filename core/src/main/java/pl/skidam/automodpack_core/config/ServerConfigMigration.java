package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import pl.skidam.automodpack_core.utils.AddressHelpers;

/**
 * Upgrades an on-disk server config through each version in sequence (V1 -> V2 -> V3) so old
 * flat file rules survive as the single required "main" group instead of being silently replaced
 * by the V3 defaults. Shared between the mod's Preload path and the standalone Server host so
 * both read an already-migrated file rather than deserializing an old version directly as V3.
 */
public class ServerConfigMigration {

	public static void migrateToLatest(Path serverConfigFile) {
		var serverConfigVersion = ConfigTools.read(serverConfigFile, Jsons.VersionConfigField.class).orElse(null);
		if (serverConfigVersion == null) return;

		if (serverConfigVersion.DO_NOT_CHANGE_IT == 1) {
			var serverConfigV1 = ConfigTools.read(serverConfigFile, Jsons.ServerConfigFieldsV1.class).orElse(null);
			var serverConfigV2 = ConfigTools.read(serverConfigFile, Jsons.ServerConfigFieldsV2.class).orElse(null);
			if (serverConfigV1 != null && serverConfigV2 != null) {
				serverConfigVersion.DO_NOT_CHANGE_IT = 2;
				serverConfigV2.DO_NOT_CHANGE_IT = 2;

				if (serverConfigV1.hostIp.isBlank()) {
					serverConfigV2.advertisedEndpointHost = "";
				} else {
					serverConfigV2.advertisedEndpointHost = AddressHelpers.parseOrigin(serverConfigV1.hostIp).getHostString();
				}

				if (serverConfigV1.hostModpackOnMinecraftPort) {
					serverConfigV2.bindPort = -1;
					serverConfigV2.advertisedEndpointPort = -1;
				} else {
					serverConfigV2.bindPort = serverConfigV1.hostPort;
					serverConfigV2.advertisedEndpointPort = serverConfigV1.hostPort;
				}

				writeConfig(serverConfigFile, serverConfigV2);
				LOGGER.info("Updated server config version to {}", serverConfigVersion.DO_NOT_CHANGE_IT);
			}
		}

		if (serverConfigVersion.DO_NOT_CHANGE_IT == 2) {
			// V3 moves the flat file rules into named groups; the existing rules become the
			// single required "main" group, so an upgraded server behaves exactly as before.
			var serverConfigV2 = ConfigTools.read(serverConfigFile, Jsons.ServerConfigFieldsV2.class).orElse(null);
			var serverConfigV3 = ConfigTools.read(serverConfigFile, Jsons.ServerConfigFieldsV3.class).orElse(null);
			if (serverConfigV2 != null && serverConfigV3 != null) {
				serverConfigVersion.DO_NOT_CHANGE_IT = 3;
				serverConfigV3.DO_NOT_CHANGE_IT = 3;

				var mainGroup = Jsons.mainGroupDeclaration();
				mainGroup.syncedFiles = serverConfigV2.syncedFiles;
				mainGroup.allowEditsInFiles = serverConfigV2.allowEditsInFiles;
				mainGroup.overwriteEditableFiles = serverConfigV2.overwriteEditableFiles;
				mainGroup.forceCopyFilesToStandardLocation = serverConfigV2.forceCopyFilesToStandardLocation;
				serverConfigV3.groups = new LinkedHashMap<>(Map.of("main", mainGroup));

				writeConfig(serverConfigFile, serverConfigV3);
				LOGGER.info("Updated server config version to {}", serverConfigVersion.DO_NOT_CHANGE_IT);
			}
		}
	}

	private static void writeConfig(Path path, Object value) {
		try {
			ConfigTools.writeAtomic(path, value);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save configuration " + path.toAbsolutePath().normalize(), e);
		}
	}
}
