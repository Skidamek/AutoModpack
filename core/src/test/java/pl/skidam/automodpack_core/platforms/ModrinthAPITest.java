package pl.skidam.automodpack_core.platforms;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class ModrinthAPITest {
	@Test
	void downloadableVersionsAreListedArchivedOrUnlisted() {
		assertTrue(ModrinthAPI.isDownloadableVersion(status("listed")));
		assertTrue(ModrinthAPI.isDownloadableVersion(status("archived")));
		assertTrue(ModrinthAPI.isDownloadableVersion(status("unlisted")));
		assertFalse(ModrinthAPI.isDownloadableVersion(status("draft")));
		assertFalse(ModrinthAPI.isDownloadableVersion(status("scheduled")));
		assertFalse(ModrinthAPI.isDownloadableVersion(status("unknown")));
		assertFalse(ModrinthAPI.isDownloadableVersion(new JsonObject()));
		assertFalse(ModrinthAPI.isDownloadableVersion(null));
	}

	@Test
	void downloadableProjectsAreApprovedArchivedOrUnlisted() {
		assertTrue(ModrinthAPI.isDownloadableProject(status("approved")));
		assertTrue(ModrinthAPI.isDownloadableProject(status("archived")));
		assertTrue(ModrinthAPI.isDownloadableProject(status("unlisted")));
		assertFalse(ModrinthAPI.isDownloadableProject(status("draft")));
		assertFalse(ModrinthAPI.isDownloadableProject(status("private")));
		assertFalse(ModrinthAPI.isDownloadableProject(status("rejected")));
		assertFalse(ModrinthAPI.isDownloadableProject(status("withheld")));
		assertFalse(ModrinthAPI.isDownloadableProject(new JsonObject()));
		assertFalse(ModrinthAPI.isDownloadableProject(null));
	}

	private static JsonObject status(String value) {
		JsonObject object = new JsonObject();
		object.addProperty("status", value);
		return object;
	}
}
