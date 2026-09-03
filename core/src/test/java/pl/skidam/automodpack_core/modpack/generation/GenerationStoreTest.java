package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;

class GenerationStoreTest {
	@TempDir
	Path tempDir;

	@Test
	void publishesRestoresCompactsAndCollects() throws Exception {
		Path objects = tempDir.resolve("objects");
		GenerationStore store = new GenerationStore(tempDir.resolve("state"), objects);
		assertTrue(store.loadCurrent().isEmpty());

		GenerationStore.Publication root = store.publish(candidate("one", "content-one"), "First");
		GenerationStore.Current current = store.loadCurrent().orElseThrow();
		assertEquals(root.entry().seq(), current.seq());
		assertEquals("First", root.entry().notes());
		assertEquals("one", current.manifest().toFields().groups.get("main").description);
		assertEquals(1, current.seq());

		GenerationStore.Publication second = store.publish(candidate("two", "content-two"), "Second");
		assertEquals(2, second.entry().seq());
		assertEquals(1, second.entry().summary().added() + second.entry().summary().changed());
		assertTrue(second.entry().changes().stream().anyMatch(change -> change.path().equals("config/example.txt") && change.toSha1().equals(sha1("content-two"))));

		// Restoring generation 1 brings back the old content and points at the restored entry.
		GenerationStore.Publication restored = store.publishRestore(1, "Back to first");
		assertEquals(3, restored.entry().seq());
		assertEquals(root.entry().contentToken(), restored.entry().contentToken());
		assertEquals(1, restored.entry().restoreOf());
		assertTrue(restored.entry().changes().stream().anyMatch(change -> change.path().equals("config/example.txt")
				&& sha1("content-two").equals(change.fromSha1()) && sha1("content-one").equals(change.toSha1())));
		assertEquals(root.entry().contentToken(), store.loadCurrent().orElseThrow().contentToken());

		// Compaction collapses the prefix into a snapshot and replay still verifies tokens.
		GenerationStore.CompactionSummary compaction = store.compact(2);
		assertEquals(1, compaction.removedEntries());
		assertEquals(3, compaction.entriesBefore());
		assertEquals(2, compaction.entriesAfter());
		assertEquals(restored.entry().contentToken(), store.loadCurrent().orElseThrow().contentToken());

		// Objects unreachable from any retained journal entry are collectable.
		Files.createDirectories(objects.resolve("ff"));
		Path orphan = objects.resolve("ff").resolve(sha1("orphan").substring(2));
		Files.write(orphan, "orphan".getBytes(StandardCharsets.UTF_8));
		GenerationStore.CollectionSummary collection = store.collectUnreachable();
		assertEquals(1, collection.deletedObjects());
		assertFalse(Files.exists(orphan));
	}

	@Test
	void contentTokenIgnoresPolicyOnlyChanges() throws Exception {
		GenerationStore store = new GenerationStore(tempDir.resolve("state"), tempDir.resolve("objects"));
		store.publish(candidate("one", "content-one"), "First");
		String before = store.loadCurrent().orElseThrow().contentToken();

		// Same bytes, different group policy: the content token must not move.
		ModpackCandidate renamed = candidate("renamed", "content-one");
		assertEquals(before, ContentTree.tokenOf(renamed.manifest()));
	}

	@Test
	void journalReplayDetectsCorruption() throws Exception {
		Path state = tempDir.resolve("state");
		Path objects = tempDir.resolve("objects");
		String first = sha1("content-one");
		String second = sha1("content-two");
		GroupManifest manifestOne = manifest("one", "content-one");
		GroupManifest manifestTwo = manifest("two", "content-two");
		String policyOne = storePolicyObject(objects, manifestOne);
		String policyTwo = storePolicyObject(objects, manifestTwo);
		JournalEntry firstEntry = new JournalEntry(1, ContentTree.tokenOf(manifestOne), policyOne, TestPacks.CREATED, "First", JournalEntry.NO_RESTORE, false,
				List.of(new JournalEntry.Change("config/example.txt", sha1("previous"), first, "content-one".length())));
		JournalEntry secondEntry = new JournalEntry(2, ContentTree.tokenOf(manifestTwo), policyTwo, TestPacks.CREATED, "Second", JournalEntry.NO_RESTORE, false,
				List.of(new JournalEntry.Change("config/example.txt", first, second, "content-two".length())));
		Files.createDirectories(state);
		Files.writeString(state.resolve("journal.jsonl"),
				new Gson().toJson(firstEntry.toFields()) + "\n" + new Gson().toJson(secondEntry.toFields()) + "\n", StandardCharsets.UTF_8);
		Path journal = state.resolve("journal.jsonl");
		Files.writeString(journal, Files.readString(journal, StandardCharsets.UTF_8).replace(second, sha1("tampered")), StandardCharsets.UTF_8);

		GenerationStore reopened = new GenerationStore(state, objects);
		assertThrows(IllegalStateException.class, reopened::loadCurrent);
	}

	@Test
	void headDocumentCarriesPolicyLedgerAndTail() throws Exception {
		GenerationStore store = new GenerationStore(tempDir.resolve("state"), tempDir.resolve("objects"));
		store.publish(candidate("one", "content-one"), "First");
		store.publish(candidate("two", "content-two"), "Second");

		Path projection = tempDir.resolve("state").resolve("current-projection.json");
		GenerationJsons.HeadDocumentFields fields = ConfigToolsRead.read(projection);
		assertEquals(2, fields.journalHead);
		assertEquals(2, fields.journal.size());
		assertEquals("Second", fields.journal.get(1).notes);
		assertEquals(sha1("content-two"), fileEntry(fields).sha1);
		assertEquals("abc1234", fields.policy.modpackId);
		assertNotNull(fields.ownershipLedger.digest);
	}

	private static ModpackJsons.CompleteModpackContentFields.GroupFileFields fileEntry(GenerationJsons.HeadDocumentFields fields) {
		return fields.policy.groups.get("main").files.get("config/example.txt");
	}

	private static GroupManifest manifest(String description, String content) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		group.files = Map.of("config/example.txt", new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length()), "config", false, sha1(content), null));
		fields.groups = new TreeMap<>(Map.of("main", group));
		return GroupManifestValidator.validate(fields);
	}

	private static String storePolicyObject(Path objects, GroupManifest manifest) throws IOException {
		byte[] bytes = pl.skidam.automodpack_core.config.ConfigTools.GSON.toJson(manifest.toFields()).getBytes(StandardCharsets.UTF_8);
		String policySha1 = HashUtils.sha1(bytes);
		Path object = DataRootResolver.objectFile(objects, policySha1);
		Files.createDirectories(object.getParent());
		Files.write(object, bytes);
		return policySha1;
	}

	private ModpackCandidate candidate(String description, String content) throws IOException {
		Path staging = Files.createDirectories(tempDir.resolve("state").resolve("staging"));
		Path staged = Files.createTempFile(staging, "candidate-", ".staged");
		Files.writeString(staged, content, StandardCharsets.UTF_8);
		String hash = sha1(content);
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		group.files = Map.of("config/example.txt", new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length()), "config", false, hash, null));
		fields.groups = new TreeMap<>(Map.of("main", group));
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		return new ModpackCandidate(manifest, new TreeMap<>(Map.of(hash, stagedObject(staged, hash, content.length()))), new TreeMap<>(), List.of(), List.of());
	}

	private pl.skidam.automodpack_core.modpack.candidate.StagedObject stagedObject(Path staged, String hash, long size) throws IOException {
		return new pl.skidam.automodpack_core.modpack.candidate.StagedObject(hash, size, staged);
	}

	private static String sha1(String content) {
		return HashUtils.sha1(content.getBytes(StandardCharsets.UTF_8));
	}

	private static final class ConfigToolsRead {
		static GenerationJsons.HeadDocumentFields read(Path path) throws IOException {
			return pl.skidam.automodpack_core.config.ConfigTools.read(path, GenerationJsons.HeadDocumentFields.class).orElseThrow();
		}
	}
}
