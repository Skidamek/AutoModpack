package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigUtilsTest {
	@Test
	void preservesPathRuleOrder() {
		Jsons.ServerConfigFieldsV3 config = new Jsons.ServerConfigFieldsV3();
		Jsons.GroupDeclaration group = new Jsons.GroupDeclaration();
		group.syncedFiles = new LinkedHashSet<>(List.of("/third", "/first", "/second"));
		group.allowEditsInFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		group.overwriteEditableFiles = new LinkedHashSet<>(List.of("third", "first", "second"));
		group.forceCopyFilesToStandardLocation = new LinkedHashSet<>(List.of("third", "first", "second"));
		config.groups = new LinkedHashMap<>(Map.of("main", group));

		ConfigUtils.normalizeServerConfig(config);

		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.syncedFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.allowEditsInFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.overwriteEditableFiles));
		assertEquals(List.of("/third", "/first", "/second"), List.copyOf(group.forceCopyFilesToStandardLocation));
	}
}
