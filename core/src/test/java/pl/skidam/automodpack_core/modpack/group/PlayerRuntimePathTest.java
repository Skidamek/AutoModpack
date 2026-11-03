package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ModpackJsons;

/** crash-reports is the game's folder for the player: refused when publishing, still loadable from packs published before. */
class PlayerRuntimePathTest {
	@Test
	void publishingIntoCrashReportsIsRefusedButAnOldPackStillLoads() {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		group.defaultSelected = true;
		group.files = Map.of("crash-reports/old.txt", new ModpackJsons.CompleteModpackContentFields.GroupFileFields("4", false, "1".repeat(40), "0"));
		fields.categories = Map.of("General", Map.of("main", group));

		GroupValidationException refused = assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validateForPublish(fields));
		assertTrue(refused.getMessage().contains("crash-reports/old.txt"));
		assertEquals(Map.of("crash-reports/old.txt", 4L), Map.of("crash-reports/old.txt", GroupManifestValidator.validate(fields).groups().get("main").files().get("crash-reports/old.txt").size()));
	}
}
