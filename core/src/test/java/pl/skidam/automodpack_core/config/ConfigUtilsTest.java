package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

class ConfigUtilsTest {
	@Test
	void preservesPathRuleOrder() {
		Jsons.ServerConfigFieldsV3 config = new Jsons.ServerConfigFieldsV3();
		Jsons.GroupDeclaration group = new Jsons.GroupDeclaration();
		group.syncedFiles = new LinkedHashSet<>(List.of("/third", "/first", "/second"));
		group.allowEditsInFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		group.overwriteEditableFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		config.groups = new LinkedHashMap<>(Map.of("main", group));

		ConfigUtils.normalizeServerConfig(config);

		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.syncedFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.allowEditsInFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.overwriteEditableFiles));
	}

	@Test
	void holepunchAvailabilityStartsAt1201OnFabric() {
		String previousVersion = Constants.MC_VERSION;
		String previousLoader = Constants.LOADER;
		try {
			Jsons.ServerConfigFieldsV3 config = new Jsons.ServerConfigFieldsV3();
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
