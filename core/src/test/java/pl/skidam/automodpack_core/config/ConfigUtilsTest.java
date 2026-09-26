package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
		group.fromServer = new LinkedHashSet<>(List.of("third", "first", "second"));
		group.editable = new LinkedHashSet<>(List.of("third", "first", "second"));
		config.modpack.categories = Map.of("General", new LinkedHashMap<>(Map.of("main", group)));

		ConfigUtils.normalizeServerConfig(config);

		assertEquals(List.of("third", "first", "second"), List.copyOf(group.fromServer));
		assertEquals(List.of("third", "first", "second"), List.copyOf(group.editable));
	}

	@Test
	void normalizesRulePathsAndKeepsSetLocalNegations() {
		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		ServerConfigJsons.GroupDeclaration group = new ServerConfigJsons.GroupDeclaration();
		group.fromServer = new LinkedHashSet<>(List.of("/mods/*.jar", "/automodpack/host-modpack/main/extra", "!kubejs/server_scripts/**", "!/kubejs/assets/**"));
		group.exclude = new LinkedHashSet<>(List.of("/automodpack/host-modpack/main/secret.bin", "!/automodpack/host-modpack/main/keep.bin"));
		group.editable = new LinkedHashSet<>(List.of("//config/**"));
		config.modpack.categories = Map.of("General", new LinkedHashMap<>(Map.of("main", group)));

		ConfigUtils.normalizeServerConfig(config);

		assertEquals(List.of("mods/*.jar", "!kubejs/server_scripts/**", "!kubejs/assets/**"), List.copyOf(group.fromServer));
		assertEquals(List.of("secret.bin", "!keep.bin"), List.copyOf(group.exclude));
		assertEquals(List.of("config/**"), List.copyOf(group.editable));
	}

	@Test
	void hostModpackRulesStripOnlyTheirOwnGroupPrefix() {
		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		ServerConfigJsons.GroupDeclaration group = new ServerConfigJsons.GroupDeclaration();
		group.fromServer = new LinkedHashSet<>(List.of("automodpack/host-modpack/main/extra", "!automodpack/host-modpack/main/skip/**"));
		group.exclude = new LinkedHashSet<>(
				List.of("automodpack/host-modpack/main/**", "automodpack/host-modpack/other/**", "/automodpack/host-modpack/main", "automodpack/host-modpack/main/**/**", "automodpack/host-modpack/main/**/*"));
		config.modpack.categories = Map.of("General", new LinkedHashMap<>(Map.of("main", group)));

		ConfigUtils.normalizeServerConfig(config);

		// Own-group synced rules are dropped entirely: the group directory is included in full, so they are redundant.
		assertEquals(List.of(), List.copyOf(group.fromServer));
		// Whole-directory remainders (empty, `**`, `**/*`, collapsed `**/**`) are dropped: exclude also matches synced paths.
		// A foreign group's rule is kept verbatim instead of being rewritten into this group's space.
		assertEquals(List.of("automodpack/host-modpack/other/**"), List.copyOf(group.exclude));
	}

	@Test
	void nullGroupAndCategoryDeclarationsAreInvalid() {
		ServerConfigJsons.ServerConfigFieldsV3 nullCategory = new ServerConfigJsons.ServerConfigFieldsV3();
		nullCategory.modpack.categories = new LinkedHashMap<>(Map.of("General", new LinkedHashMap<>(Map.of("main", new ServerConfigJsons.GroupDeclaration()))));
		nullCategory.modpack.categories.put("Broken", null);
		assertThrows(ConfigTools.ConfigParseException.class, () -> ConfigUtils.normalizeServerConfig(nullCategory));

		ServerConfigJsons.ServerConfigFieldsV3 nullGroup = new ServerConfigJsons.ServerConfigFieldsV3();
		Map<String, ServerConfigJsons.GroupDeclaration> groups = new LinkedHashMap<>();
		groups.put("main", null);
		nullGroup.modpack.categories = new LinkedHashMap<>(Map.of("General", groups));
		assertThrows(ConfigTools.ConfigParseException.class, () -> ConfigUtils.normalizeServerConfig(nullGroup));
	}
}
