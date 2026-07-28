package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

class ConfigUtilsTest {
	@Test
	void preservesPathRuleOrder() {
		Jsons.ServerConfigFieldsV2 config = new Jsons.ServerConfigFieldsV2();
		config.syncedFiles = new LinkedHashSet<>(List.of("/third", "/first", "/second"));
		config.allowEditsInFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		config.overwriteEditableFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		config.forceCopyFilesToStandardLocation = new LinkedHashSet<>(List.of("third", "first", "second"));

		ConfigUtils.normalizeServerConfig(config);

		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(config.syncedFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(config.allowEditsInFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(config.overwriteEditableFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(config.forceCopyFilesToStandardLocation));
	}

	@Test
	void holepunchAvailabilityStartsAt1201OnFabric() {
		String previousVersion = Constants.MC_VERSION;
		String previousLoader = Constants.LOADER;
		try {
			Jsons.ServerConfigFieldsV2 config = new Jsons.ServerConfigFieldsV2();
			config.bindPort = 24444;

			Constants.MC_VERSION = "1.20.1";
			Constants.LOADER = "fabric";

			config.connectionMode = ModpackConnectionMode.HOLEPUNCH;
			ConfigUtils.normalizeServerConfig(config);
			assertEquals(config.connectionMode, ModpackConnectionMode.HOLEPUNCH);
			assertEquals(24444, config.bindPort);

			Constants.LOADER = "forge";

			config.connectionMode = ModpackConnectionMode.HOLEPUNCH;
			ConfigUtils.normalizeServerConfig(config);
			assertEquals(config.connectionMode, ModpackConnectionMode.MAGIC_PACKET);
			assertEquals(24444, config.bindPort);

			Constants.MC_VERSION = "1.19.2";
			Constants.LOADER = "fabric";

			config.connectionMode = ModpackConnectionMode.HOLEPUNCH;
			ConfigUtils.normalizeServerConfig(config);
			assertEquals(config.connectionMode, ModpackConnectionMode.MAGIC_PACKET);
			assertEquals(24444, config.bindPort);
		} finally {
			Constants.MC_VERSION = previousVersion;
			Constants.LOADER = previousLoader;
		}
	}
}
