package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.generation.TestPacks;

class SelectedModpackTargetTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void invalidPersistedIntentIsReportedWithStructuredResolution() throws Exception {
		GroupManifest.Category exclusive = new GroupManifest.Category("Shaders", "", CategoryPickMode.AT_MOST_ONE);
		GroupManifest.Group first = new GroupManifest.Group("", "", "shaders", GroupSelectionMode.OPTIONAL, new TreeSet<>(), Set.of(), new TreeMap<>());
		GroupManifest.Group second = new GroupManifest.Group("", "", "shaders", GroupSelectionMode.RECOMMENDED, new TreeSet<>(), Set.of(), new TreeMap<>());
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", Map.of("shaders", exclusive), new TreeMap<>(Map.of("first", first, "second", second)));
		ClientSelectionStore store = new ClientSelectionStore(temporaryDirectory.resolve("selection.json"));
		SelectionIntent persisted = SelectionIntent.ofEnabled(Set.of("first", "second"));
		store.put(manifest.modpackId(), persisted);

		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> SelectedModpackTarget.prepare(TestPacks.head(manifest), store, ClientPlatform.LINUX));

		assertEquals(persisted, failure.resolution().intent());
		assertTrue(failure.resolution().selectedGroups().isEmpty());
		assertEquals(GroupResolution.Status.CONFLICT, failure.resolution().resolution("first").status());
		assertEquals(GroupResolution.Status.CONFLICT, failure.resolution().resolution("second").status());
	}
}
