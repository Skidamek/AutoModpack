package pl.skidam.automodpack_core.modpack.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Shared fixture for tests: builds policy documents, pack documents, head document fields, and staged client history. */
public final class TestPacks {
	public static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

	private TestPacks() {}

	public static GroupManifest manifest(String description, String path, String content) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		String sha1 = HashUtils.sha1(content.getBytes(StandardCharsets.UTF_8));
		group.files = new TreeMap<>(Map.of(path, new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length()), "config", false, sha1, null)));
		fields.groups = new TreeMap<>(Map.of("main", group));
		return GroupManifestValidator.validate(fields);
	}

	public static String policySha1(GroupManifest manifest) {
		return HashUtils.sha1(ConfigTools.GSON.toJson(manifest.toFields()).getBytes(StandardCharsets.UTF_8));
	}

	public static PackDocument document(GroupManifest manifest) {
		return PackDocument.create(manifest, policySha1(manifest), CREATED, null);
	}

	public static PackDocument document(GroupManifest manifest, OwnershipLedger parent, Instant createdAt) {
		return PackDocument.create(manifest, policySha1(manifest), createdAt, parent);
	}

	public static GenerationJsons.HeadDocumentFields head(GroupManifest manifest) {
		PackDocument document = document(manifest);
		GenerationJsons.HeadDocumentFields fields = new GenerationJsons.HeadDocumentFields();
		fields.contentToken = document.contentToken();
		fields.policySha1 = document.policySha1();
		fields.createdAt = document.createdAt().toString();
		fields.ownershipLedger = document.ownershipLedger().toFields();
		fields.policy = manifest.toFields();
		return fields;
	}

	/**
	 * Stages one generation into the client's durable history the way a real sync leaves it: the policy document in
	 * the client CAS and a journal entry appended to the pack's mirror, with changes folded from the staged history.
	 */
	public static void stageGeneration(ClientStorage storage, PackDocument document) throws IOException {
		ClientObjectStore.storeObject(storage, document.policySha1(), ConfigTools.GSON.toJson(document.manifest().toFields()).getBytes(StandardCharsets.UTF_8));
		Path mirror = storage.historyJournalFile(document.manifest().modpackId());
		Journal journal = Journal.open(mirror);
		for (JournalEntry entry : journal.entries()) if (entry.contentToken().equals(document.contentToken())) return;
		ContentTree previous = journal.isEmpty() ? ContentTree.empty() : journal.treeAt(journal.head().seq());
		ContentTree next = ContentTree.fromManifest(document.manifest());
		journal.append(new JournalEntry(journal.isEmpty() ? 1 : journal.head().seq() + 1, document.contentToken(), document.policySha1(), document.createdAt(), "", JournalEntry.NO_RESTORE,
				journal.isEmpty(), diff(previous, next)));
	}

	private static List<JournalEntry.Change> diff(ContentTree previous, ContentTree next) {
		List<JournalEntry.Change> changes = new ArrayList<>();
		for (var entry : next.files().entrySet()) {
			ContentTree.ContentFile old = previous.files().get(entry.getKey());
			if (old == null) changes.add(JournalEntry.Change.added(entry.getKey(), entry.getValue().sha1(), entry.getValue().size()));
			else changes.add(new JournalEntry.Change(entry.getKey(), old.sha1(), entry.getValue().sha1(), entry.getValue().size()));
		}
		for (var entry : previous.files().entrySet())
			if (!next.files().containsKey(entry.getKey())) changes.add(JournalEntry.Change.removed(entry.getKey(), entry.getValue().sha1()));
		changes.sort(Comparator.comparing(JournalEntry.Change::path));
		return changes;
	}
}
