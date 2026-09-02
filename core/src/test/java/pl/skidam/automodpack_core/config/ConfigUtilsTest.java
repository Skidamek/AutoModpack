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
		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		ServerConfigJsons.GroupDeclaration group = new ServerConfigJsons.GroupDeclaration();
		group.syncedFiles = new LinkedHashSet<>(List.of("/third", "/first", "/second"));
		group.allowEditsInFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		config.groups = new LinkedHashMap<>(Map.of("main", group));

		ConfigUtils.normalizeServerConfig(config);

		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.syncedFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.allowEditsInFiles));
	}

	@Test
	void holepunchRequiresLoginStartProfileUuid() {
		String previousVersion = Constants.MC_VERSION;
		String previousLoader = Constants.LOADER;
		try {
			ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
			config.bindPort = 24444;

			Constants.MC_VERSION = "1.18.2";
			Constants.LOADER = "forge";
			config.connectionMode = ModpackConnectionMode.HOLEPUNCH;
			ConfigUtils.normalizeServerConfig(config);
			assertEquals(ModpackConnectionMode.MAGIC_PACKET, config.connectionMode);

			for (String[] target : new String[][]{{"1.19.2", "fabric"}, {"1.20.1", "forge"}, {"26.2", "neoforge"}}) {
				Constants.MC_VERSION = target[0];
				Constants.LOADER = target[1];
				config.connectionMode = ModpackConnectionMode.HOLEPUNCH;
				ConfigUtils.normalizeServerConfig(config);
				assertEquals(ModpackConnectionMode.HOLEPUNCH, config.connectionMode);
			}

			config.connectionMode = null;
			ConfigUtils.normalizeServerConfig(config);
			assertEquals(ModpackConnectionMode.HOLEPUNCH, config.connectionMode);
			assertEquals(24444, config.bindPort);
		} finally {
			Constants.MC_VERSION = previousVersion;
			Constants.LOADER = previousLoader;
		}
	}
}
