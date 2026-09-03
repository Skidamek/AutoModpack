package pl.skidam.automodpack_core.modpack.generation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Shared fixture for tests: builds policy documents, pack documents, and head document fields. */
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
}
