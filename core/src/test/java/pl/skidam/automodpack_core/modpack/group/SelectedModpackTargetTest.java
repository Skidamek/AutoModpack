package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;

class SelectedModpackTargetTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void invalidPersistedIntentFallsBackToDefaultsButRemainsExpectedPriorIntent() throws Exception {
		GroupManifest.Group first = group(Set.of());
		GroupManifest.Group second = new GroupManifest.Group("", "", "", false, true, new TreeSet<>(Set.of("first")), new TreeSet<>(), Set.of(), new TreeMap<>());
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("first", first, "second", second)), new TreeMap<>());
		GenerationRecord record = GenerationRecord.create(manifest, null, Instant.parse("2026-01-01T00:00:00Z"), "");
		ClientSelectionStore store = new ClientSelectionStore(temporaryDirectory.resolve("selection.json"));
		SelectionIntent persisted = new SelectionIntent(Set.of("first", "second"));
		store.compareAndSet(manifest.modpackId(), null, persisted);

		SelectedModpackTarget target = SelectedModpackTarget.prepare(record.toFields(), store, ClientPlatform.LINUX);

		assertEquals(Set.of("first", "second"), target.expectedPriorIntent().requestedGroups());
		assertEquals(Set.of("second"), target.selection().intent().requestedGroups());
		assertEquals(Set.of("second"), target.selection().selectedGroups());
	}

	private static GroupManifest.Group group(Set<String> breaksWith) {
		return new GroupManifest.Group("", "", "", false, false, new TreeSet<>(breaksWith), new TreeSet<>(), Set.of(), new TreeMap<>());
	}
}
