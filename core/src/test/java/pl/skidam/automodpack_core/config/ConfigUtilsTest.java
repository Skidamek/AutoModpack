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
		group.syncedFiles = new LinkedHashSet<>(List.of("/third", "/first", "/second"));
		group.allowEditsInFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		config.groups = new LinkedHashMap<>(Map.of("main", group));

		ConfigUtils.normalizeServerConfig(config);

		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.syncedFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.allowEditsInFiles));
	}
}
