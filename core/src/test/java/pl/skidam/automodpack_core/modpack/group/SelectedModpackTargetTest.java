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
		GroupManifest.Group first = group(Set.of());
		GroupManifest.Group second = new GroupManifest.Group("", "", "", false, true, new TreeSet<>(Set.of("first")), new TreeSet<>(), Set.of(), new TreeMap<>());
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("first", first, "second", second)));
		ClientSelectionStore store = new ClientSelectionStore(temporaryDirectory.resolve("selection.json"));
		SelectionIntent persisted = new SelectionIntent(Set.of("first", "second"));
		store.compareAndSet(manifest.modpackId(), null, persisted);

		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> SelectedModpackTarget.prepare(TestPacks.head(manifest), store, ClientPlatform.LINUX));

		assertEquals(persisted, failure.resolution().intent());
		assertEquals(Set.of("first", "second"), failure.resolution().selectedGroups());
		assertEquals(GroupResolution.Status.CONFLICT, failure.resolution().resolution("first").status());
		assertEquals(GroupResolution.Status.CONFLICT, failure.resolution().resolution("second").status());
	}

	private static GroupManifest.Group group(Set<String> breaksWith) {
		return new GroupManifest.Group("", "", "", false, false, new TreeSet<>(breaksWith), new TreeSet<>(), Set.of(), new TreeMap<>());
	}
}
