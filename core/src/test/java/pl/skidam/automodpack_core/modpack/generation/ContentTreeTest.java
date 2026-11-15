package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.utils.HashUtils;

class ContentTreeTest {
	@Test
	void uniquePathTokenStaysTheV1SingleTripleEncoding() {
		assertEquals("fbc5bb0f30aefdc5f124b2243c1f155b4e394cbc", ContentTree.tokenOf(TestPacks.manifest("d", "config/example.txt", "hello")));
	}

	@Test
	void exclusiveVariantsAreTwoTriplesAndShareNoDrop() {
		GroupManifest manifest = exclusive("performance-rrls", "fantasy-rrls");
		ContentTree tree = assertDoesNotThrow(() -> ContentTree.fromManifest(manifest));
		assertEquals(2, tree.size());
		assertEquals(Set.of(sha1("performance-rrls"), sha1("fantasy-rrls")), tree.objectHashes());
	}

	@Test
	void identicalBytesAtOnePathCollapseToOneTriple() {
		GroupManifest shared = exclusive("same-bytes", "same-bytes");
		GroupManifest one = TestPacks.manifest("only", "config/example.txt", "same-bytes");
		assertEquals(1, ContentTree.fromManifest(shared).size());
		assertEquals(ContentTree.tokenOf(one), ContentTree.tokenOf(shared));
	}

	@Test
	void aNewVariantAtAnExistingPathDiffsAsAdded() {
		ContentTree before = ContentTree.fromManifest(TestPacks.manifest("one", "config/example.txt", "content-one"));
		ContentTree after = ContentTree.fromManifest(exclusive("content-one", "content-two"));
		List<JournalEntry.Change> changes = before.diff(after);
		assertEquals(1, changes.size());
		assertEquals(JournalEntry.Change.Kind.ADDED, changes.get(0).kind());
		assertEquals(sha1("content-two"), changes.get(0).toSha1());
		assertEquals("config/example.txt", changes.get(0).path());
	}

	private static GroupManifest exclusive(String performanceContent, String fantasyContent) {
		var performance = group(performanceContent, "fantasy");
		var fantasy = group(fantasyContent, "performance");
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.categories = Map.of("General", Map.of("performance", performance, "fantasy", fantasy));
		return GroupManifestValidator.validate(fields);
	}

	private static ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group(String content, String breaksWith) {
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = false;
		group.defaultSelected = false;
		group.breaksWith = Set.of(breaksWith);
		group.files = Map.of("config/example.txt", new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length()), false, sha1(content), null));
		return group;
	}

	private static String sha1(String content) {
		return HashUtils.sha1(content.getBytes(StandardCharsets.UTF_8));
	}
}
