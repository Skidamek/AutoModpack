package pl.skidam.automodpack_core.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;

class ChangeBrowserProjectionTest {
	private static final String HASH = "1111111111111111111111111111111111111111";

	@Test
	void treeKeepsOnlyMatchingAncestorsAndAggregatesKinds() {
		ChangeSet changes = changes();

		ChangeBrowserProjection.Projection projection = ChangeBrowserProjection.project(changes, ChangeBrowserProjection.Mode.TREE,
				new ChangeBrowserProjection.Filter("SUB", Set.of("CONFIG"), Set.of("main")));

		assertEquals(List.of("config", "config/sub", "config/sub/changed.json"), projection.rows().stream().map(ChangeBrowserProjection.Row::path).toList());
		ChangeBrowserProjection.FolderRow folder = projection.folders().get(1);
		assertEquals(1, folder.aggregate().fileCount());
		assertEquals(8, folder.aggregate().byteCount());
		assertEquals(new ChangeBrowserProjection.KindAggregate(1, 8), folder.aggregate().forKind(ChangeSet.Kind.MODIFIED));
		assertEquals(1, projection.total().fileCount());
	}

	@Test
	void listUsesTheSameFilterAndDoesNotExposeTreeAncestors() {
		ChangeBrowserProjection.Projection projection = ChangeBrowserProjection.project(changes(), ChangeBrowserProjection.Mode.LIST,
				new ChangeBrowserProjection.Filter("", Set.of(), Set.of("optional")));

		assertEquals(List.of("mods/removed.jar"), projection.files().stream().map(ChangeBrowserProjection.FileRow::path).toList());
		assertEquals(0, projection.files().get(0).depth());
		assertEquals(Set.of("mod"), projection.files().get(0).contentKinds());
		assertEquals(ChangeSet.Kind.REMOVED, projection.files().get(0).kind());
	}

	@Test
	void catalogueChangesUseTheSameProjectionAsDiffChanges() {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(5, "mod", false, false, HASH, null);
		GroupManifest.Group group = new GroupManifest.Group("Main", "", "", "", false, true, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>(Map.of("mods/example.jar", file)));

		ChangeSet catalogue = ChangeSet.catalogue(new GroupManifest("pack", "Pack", "1", "fabric", "1", "1.21", new TreeMap<>(Map.of("main", group))));

		ChangeBrowserProjection.FileRow row = ChangeBrowserProjection.project(catalogue, ChangeBrowserProjection.Mode.LIST).files().get(0);
		assertEquals(ChangeSet.Kind.PRESERVED, row.kind());
		assertEquals(Set.of("mod"), row.contentKinds());
		assertEquals(new ChangeBrowserProjection.KindAggregate(1, 5), ChangeBrowserProjection.project(catalogue, ChangeBrowserProjection.Mode.TREE).total().forKind(ChangeSet.Kind.PRESERVED));
	}

	@Test
	void infersPathContentKindWhenAProducerHasNoExplicitType() {
		ChangeSet.Change change = new ChangeSet.Change("config/options.json", ChangeSet.Kind.MODIFIED,
				List.of(new ChangeSet.Occurrence("projection", "config/options.json", 3)));
		ChangeSet.Change mod = new ChangeSet.Change("mods/example.jar", ChangeSet.Kind.ADDED,
				List.of(new ChangeSet.Occurrence("projection", "mods/example.jar", 3)));

		assertEquals(Set.of("config"), new ChangeBrowserProjection.FileRow(change.logicalPath(), 0, change.kind(), change.occurrences()).contentKinds());
		assertEquals(Set.of("mod"), new ChangeBrowserProjection.FileRow(mod.logicalPath(), 0, mod.kind(), mod.occurrences()).contentKinds());
	}

	@Test
	void projectionsAndFiltersAreImmutable() {
		ChangeBrowserProjection.Projection projection = ChangeBrowserProjection.project(changes(), ChangeBrowserProjection.Mode.TREE);

		assertThrows(UnsupportedOperationException.class, () -> projection.rows().clear());
		assertThrows(UnsupportedOperationException.class, () -> projection.total().byKind().clear());
	}

	@Test
	void treeCollapseKeepsTheFolderAndFullTotals() {
		ChangeBrowserProjection.Projection projection = ChangeBrowserProjection.project(changes(), ChangeBrowserProjection.Mode.TREE);
		ChangeBrowserProjection.Projection collapsed = projection.collapse(Set.of("config"));

		assertEquals(List.of("config", "mods", "mods/removed.jar", "resourcepacks", "resourcepacks/kept.zip"), collapsed.rows().stream().map(ChangeBrowserProjection.Row::path).toList());
		assertEquals(projection.total(), collapsed.total());
	}

	private static ChangeSet changes() {
		return ChangeSet.of(List.of(
				change("config/sub/changed.json", ChangeSet.Kind.MODIFIED, "main", 8, "config"),
				change("config/added.json", ChangeSet.Kind.ADDED, "main", 4, "config"),
				change("mods/removed.jar", ChangeSet.Kind.REMOVED, "optional", 3, "mod"),
				change("resourcepacks/kept.zip", ChangeSet.Kind.PRESERVED, "main", 9, "resourcepack")));
	}

	private static ChangeSet.Change change(String path, ChangeSet.Kind kind, String group, long size, String contentKind) {
		return new ChangeSet.Change(path, kind, List.of(new ChangeSet.Occurrence(group, path, size, null, HASH, contentKind, List.of())));
	}
}
