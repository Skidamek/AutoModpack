package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

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
}
