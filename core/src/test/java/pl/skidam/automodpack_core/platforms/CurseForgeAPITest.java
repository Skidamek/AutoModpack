package pl.skidam.automodpack_core.platforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class CurseForgeAPITest {
	@Test
	void placeholderSlugIsTheDeletedProjectForm() {
		assertTrue(CurseForgeAPI.isPlaceholderSlug("project-1318792"));
		assertTrue(CurseForgeAPI.isPlaceholderSlug("PROJECT-12"));
		assertFalse(CurseForgeAPI.isPlaceholderSlug("explosive-enhancement"));
		assertFalse(CurseForgeAPI.isPlaceholderSlug("project-"));
		assertFalse(CurseForgeAPI.isPlaceholderSlug("project-12a"));
		assertFalse(CurseForgeAPI.isPlaceholderSlug(null));
	}

	@Test
	void placeholderProjectPageReadsTheLastPathSegment() {
		assertTrue(CurseForgeAPI.isPlaceholderProjectPage("https://www.curseforge.com/minecraft/mc-mods/project-1318792"));
		assertTrue(CurseForgeAPI.isPlaceholderProjectPage("https://www.curseforge.com/minecraft/mc-mods/project-1318792/"));
		assertFalse(CurseForgeAPI.isPlaceholderProjectPage("https://www.curseforge.com/minecraft/mc-mods/explosive-enhancement"));
		assertFalse(CurseForgeAPI.isPlaceholderProjectPage(null));
		assertFalse(CurseForgeAPI.isPlaceholderProjectPage(""));
	}

	@Test
	void publicPagePrefersARealWebsiteThenARealSlug() {
		assertEquals("https://www.curseforge.com/minecraft/mc-mods/sodium", CurseForgeAPI.publicProjectPageUrl("https://www.curseforge.com/minecraft/mc-mods/sodium", "project-394468"));
		assertEquals("https://www.curseforge.com/minecraft/mc-mods/sodium", CurseForgeAPI.publicProjectPageUrl(null, "sodium"));
		assertNull(CurseForgeAPI.publicProjectPageUrl("https://www.curseforge.com/minecraft/mc-mods/project-1318792", "project-1318792"));
	}

	@Test
	void listedProjectsRequireIsAvailable() {
		JsonObject listed = new JsonObject();
		listed.addProperty("isAvailable", true);
		assertTrue(CurseForgeAPI.isListedProject(listed));
		JsonObject deleted = new JsonObject();
		deleted.addProperty("isAvailable", false);
		assertFalse(CurseForgeAPI.isListedProject(deleted));
		assertFalse(CurseForgeAPI.isListedProject(new JsonObject()));
		assertFalse(CurseForgeAPI.isListedProject(null));
	}
}
