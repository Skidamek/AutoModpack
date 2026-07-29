package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;

class GenerationIdentityTest {
	@Test
	void shuffledCatalogueOrderDoesNotChangeStateDigest() {
		var first = catalogue("main", "main description");
		var second = catalogue("main", "main description");
		var firstGroups = new LinkedHashMap<>(first.groups);
		first.groups = new LinkedHashMap<>();
		first.groups.putAll(firstGroups);
		var secondGroups = new LinkedHashMap<>(second.groups);
		second.groups = new LinkedHashMap<>();
		second.groups.put("optional", secondGroups.get("optional"));
		second.groups.put("main", secondGroups.get("main"));
		assertEquals(GenerationIdentity.stateDigest(GroupManifestValidator.validate(first)), GenerationIdentity.stateDigest(GroupManifestValidator.validate(second)));
	}

	@Test
	void metadataChangeChangesStateDigest() {
		GroupManifest first = GroupManifestValidator.validate(catalogue("main", "first"));
		GroupManifest second = GroupManifestValidator.validate(catalogue("main", "second"));
		assertNotEquals(GenerationIdentity.stateDigest(first), GenerationIdentity.stateDigest(second));
	}

	@Test
	void publicationMetadataChangesGenerationIdButNotStateDigest() {
		GroupManifest manifest = GroupManifestValidator.validate(catalogue("main", "same"));
		GenerationRecord first = GenerationRecord.create(manifest, "", Instant.parse("2026-01-01T00:00:00Z"), "");
		GenerationRecord second = GenerationRecord.create(manifest, "0".repeat(40), Instant.parse("2026-01-02T00:00:00Z"), "note\n");
		assertEquals(first.metadata().stateDigest(), second.metadata().stateDigest());
		assertNotEquals(first.metadata().generationId(), second.metadata().generationId());
		assertEquals(GenerationIdentity.patchNotesDigest("note\n"), second.metadata().patchNotesDigest());
	}

	private static Jsons.CompleteModpackContentFields catalogue(String mainId, String description) {
		var fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.selectionTags = Map.of();
		fields.nonModpackFilesToDelete = java.util.Set.of();
		var main = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		main.description = description;
		main.files = Map.of();
		var optional = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		optional.files = Map.of();
		fields.groups = new LinkedHashMap<>();
		fields.groups.put(mainId, main);
		fields.groups.put("optional", optional);
		return fields;
	}
}
