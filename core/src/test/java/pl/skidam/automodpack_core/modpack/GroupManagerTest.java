package pl.skidam.automodpack_core.modpack;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

class GroupManagerTest {

	@TempDir
	Path testFilesDir;
	Path originalContentFile;

	@BeforeEach
	void setUp() {
		originalContentFile = Constants.hostModpackContentFile;
		Constants.hostModpackContentFile = testFilesDir.resolve("automodpack-content.json");
	}

	@AfterEach
	void tearDown() {
		Constants.hostModpackContentFile = originalContentFile;
	}

	@Test
	void acceptsOrdinaryNames() {
		assertDoesNotThrow(() -> GroupManager.validateName("extras"));
		assertDoesNotThrow(() -> GroupManager.validateName("Animated Entities"));
		assertDoesNotThrow(() -> GroupManager.validateName("shader-pack_2"));
	}

	@Test
	void rejectsBlankNames() {
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName(""));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("   "));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName(null));
	}

	@Test
	void rejectsSurroundingWhitespaceAndDots() {
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName(" extras"));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("extras "));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("extras."));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("."));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName(".."));
	}

	@Test
	void rejectsInvalidWindowsFilenameCharacters() {
		for (String invalid : List.of("a<b", "a>b", "a:b", "a\"b", "a/b", "a\\b", "a|b", "a?b", "a*b")) {
			assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName(invalid), invalid);
		}
	}

	@Test
	void rejectsReservedDeviceNamesCaseInsensitively() {
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("CON"));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("con"));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("Com3"));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("lpt9"));
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("NUL.txt"));
	}

	@Test
	void rejectsNamesLongerThanLimit() {
		assertThrows(IllegalArgumentException.class, () -> GroupManager.validateName("a".repeat(101)));
		assertDoesNotThrow(() -> GroupManager.validateName("a".repeat(100)));
	}

	@Test
	void createAndDeleteGroupFolders() throws IOException {
		Path groupDir = testFilesDir.resolve("extras");
		GroupManager.createGroupFolders(groupDir);

		assertTrue(Files.isDirectory(groupDir.resolve("mods")));
		assertTrue(Files.isDirectory(groupDir.resolve("resourcepacks")));
		assertTrue(Files.isDirectory(groupDir.resolve("shaderpacks")));

		Files.writeString(groupDir.resolve("mods/some-mod.jar"), "a");

		GroupManager.deleteGroupFolders(groupDir);
		assertFalse(Files.exists(groupDir));
	}

	@Test
	void deleteGroupFoldersIsNoOpWhenMissing() {
		assertDoesNotThrow(() -> GroupManager.deleteGroupFolders(testFilesDir.resolve("does-not-exist")));
	}

	@Test
	void countGroupIsEmptyWithoutGeneratedManifest() {
		assertEquals(GroupManager.GroupCounts.EMPTY, GroupManager.countGroup("extras"));
	}

	@Test
	void countGroupTalliesByType() throws IOException {
		writeManifest("extras", Set.of(
				item("/mods/a.jar", "mod"),
				item("/mods/b.jar", "mod"),
				item("/resourcepacks/pack.zip", "resourcepack"),
				item("/shaderpacks/shader.zip", "shader"),
				item("/config/other.json", "config")),
				Set.of("/mods/a.jar", "/mods/b.jar", "/resourcepacks/pack.zip", "/shaderpacks/shader.zip"));

		assertEquals(new GroupManager.GroupCounts(2, 1, 1), GroupManager.countGroup("extras"));
	}

	@Test
	void listGroupFilesFiltersByTypeAndSorts() throws IOException {
		writeManifest("extras", Set.of(
				item("/mods/zebra.jar", "mod"),
				item("/mods/alpha.jar", "mod"),
				item("/resourcepacks/pack.zip", "resourcepack")),
				Set.of("/mods/zebra.jar", "/mods/alpha.jar", "/resourcepacks/pack.zip"));

		assertEquals(List.of("/mods/alpha.jar", "/mods/zebra.jar"), GroupManager.listGroupFiles("extras", "mod"));
	}

	private static Jsons.ModpackContentFields.ModpackContentItem item(String file, String type) {
		return new Jsons.ModpackContentFields.ModpackContentItem(file, "1", type, false, false, false, "sha1", null);
	}

	private void writeManifest(String groupId, Set<Jsons.ModpackContentFields.ModpackContentItem> items, Set<String> groupFiles) throws IOException {
		Jsons.ModpackContentFields manifest = new Jsons.ModpackContentFields(items);
		Jsons.ModpackContentFields.ModpackGroupFields group = new Jsons.ModpackContentFields.ModpackGroupFields();
		group.files = groupFiles;
		manifest.groups = Map.of(groupId, group);
		ModpackContentTools.write(Constants.hostModpackContentFile, manifest);
	}
}
