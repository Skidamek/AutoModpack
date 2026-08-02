package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;

class GenerationDiffTest {
	@Test
	void reportsAllFileClassesMetadataAndCanonicalOrder() {
		GroupManifest parent = manifest("parent", Map.of("z-removed", file("1", "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null),
				"b-modified", file("1", "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null),
				"a-metadata", file("1", "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", "old")), "old description", "old-tag",
				"86f7e437faa5a7fce15d1ddcb9eaeaea377667b8");
		GroupManifest child = manifest("child", Map.of("a-added", file("1", "e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98", null),
				"b-modified", file("1", "e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98", null),
				"a-metadata", file("1", "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", "new")), "new description", "new-tag",
				"e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98");

		GenerationDiff diff = GenerationDiff.between(parent, child);

		assertEquals(List.of("a-added", "a-metadata", "b-modified", "z-removed"), diff.files().stream().map(GenerationDiff.FileChange::logicalPath).toList());
		assertEquals(List.of(GenerationDiff.FileClassification.ADDED, GenerationDiff.FileClassification.METADATA_ONLY,
				GenerationDiff.FileClassification.MODIFIED, GenerationDiff.FileClassification.REMOVED), diff.files().stream().map(GenerationDiff.FileChange::classification).toList());
		assertEquals(List.of("main"), diff.groupMetadata().modified());
		assertEquals(List.of("tag"), diff.selectionTagMetadata().modified());
		assertEquals(new GenerationDiff.Summary(1, 1, 1, 1, 3), diff.summary());
		assertEquals(List.of("modpackName"), diff.packMetadata().modified());
	}

	@Test
	void reportsGroupTagMetadataChanges() {
		GroupManifest parent = taggedManifest("old-tag");
		GroupManifest child = taggedManifest("new-tag");

		GenerationDiff diff = GenerationDiff.between(parent, child);

		assertEquals(List.of("main"), diff.groupMetadata().modified());
		assertFalse(diff.isEmpty());
	}

	@Test
	void groupMoveIsRemoveAndAddAndEqualManifestIsEmpty() {
		GroupManifest parent = manifest("same", Map.of("moved.txt", file("1", "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null)), "", "", "");
		GroupManifest moved = manifestWithGroups("same", Map.of("main", Map.of(), "optional", Map.of("moved.txt", file("1", "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null))));
		GenerationDiff diff = GenerationDiff.between(parent, moved);
		assertEquals(2, diff.files().size());
		assertTrue(diff.files().stream().allMatch(change -> change.classification() == GenerationDiff.FileClassification.ADDED
				|| change.classification() == GenerationDiff.FileClassification.REMOVED));
		assertTrue(GenerationDiff.between(parent, parent).isEmpty());
	}

	private static GroupManifest taggedManifest(String tag) {
		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.selectionTags = Map.of(tag, tag(tag));
		var group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		group.tag = tag;
		group.files = Map.of();
		fields.groups = Map.of("main", group);
		return GroupManifestValidator.validate(fields);
	}

	private static GroupManifest manifest(String id, Map<String, Jsons.CompleteModpackContentFields.GroupFileFields> files, String description, String tag,
			String deletion) {
		return manifestWithGroups(id, Map.of("main", files), description, tag, deletion);
	}

	private static GroupManifest manifestWithGroups(String id, Map<String, Map<String, Jsons.CompleteModpackContentFields.GroupFileFields>> groups) {
		return manifestWithGroups(id, groups, "", "", "");
	}

	private static GroupManifest manifestWithGroups(String id, Map<String, Map<String, Jsons.CompleteModpackContentFields.GroupFileFields>> groups, String description,
			String tag, String deletion) {
		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.modpackName = id;
		fields.selectionTags = tag.isEmpty() ? Map.of() : Map.of("tag", tag(tag));
		Map<String, Jsons.CompleteModpackContentFields.ModpackGroupFields> declarations = new LinkedHashMap<>();
		for (var entry : groups.entrySet()) {
			var group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
			group.description = entry.getKey().equals("main") ? description : "";
			group.files = entry.getValue();
			declarations.put(entry.getKey(), group);
		}
		fields.groups = declarations;
		return GroupManifestValidator.validate(fields);
	}

	private static Jsons.CompleteModpackContentFields.SelectionTagFields tag(String description) {
		var tag = new Jsons.CompleteModpackContentFields.SelectionTagFields();
		tag.description = description;
		return tag;
	}

	private static Jsons.CompleteModpackContentFields.GroupFileFields file(String size, String hash, String murmur) {
		return new Jsons.CompleteModpackContentFields.GroupFileFields(size, "other", false, false, false, hash, murmur);
	}
}
