package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigUtilsTest {
	@Test
	void preservesPathRuleOrder() {
		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		ServerConfigJsons.GroupDeclaration group = new ServerConfigJsons.GroupDeclaration();
		group.syncedFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		group.allowEditsInFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		config.groups = new LinkedHashMap<>(Map.of("main", group));

		ConfigUtils.normalizeServerConfig(config);

		assertEquals(List.of("third", "first", "second"), List.copyOf(group.syncedFiles));
		assertEquals(List.of("third", "first", "second"), List.copyOf(group.allowEditsInFiles));
	}

	@Test
	void normalizesRulePathsAndKeepsSetLocalNegations() {
		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		ServerConfigJsons.GroupDeclaration group = new ServerConfigJsons.GroupDeclaration();
		group.syncedFiles = new LinkedHashSet<>(List.of("/mods/*.jar", "/automodpack/host-modpack/main/extra", "!kubejs/server_scripts/**", "!/kubejs/assets/**"));
		group.excludedFiles = new LinkedHashSet<>(List.of("/automodpack/host-modpack/main/secret.bin", "!/automodpack/host-modpack/main/keep.bin"));
		group.allowEditsInFiles = new LinkedHashSet<>(List.of("//config/**"));
		config.groups = new LinkedHashMap<>(Map.of("main", group));

		ConfigUtils.normalizeServerConfig(config);

		assertEquals(List.of("mods/*.jar", "!kubejs/server_scripts/**", "!kubejs/assets/**"), List.copyOf(group.syncedFiles));
		assertEquals(List.of("secret.bin", "!keep.bin"), List.copyOf(group.excludedFiles));
		assertEquals(List.of("config/**"), List.copyOf(group.allowEditsInFiles));
	}
}
