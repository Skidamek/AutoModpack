package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.ConflictAction;
import pl.skidam.automodpack_core.utils.HashUtils;

class QuarantineArchiveTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void retryAfterManifestCommitKeepsPayloadAndRemovesSource() throws Exception {
		ClientStorage storage = ClientStorage.fromGameDirectory(temporaryDirectory.resolve("game"));
		storage.ensureRoots();
		Files.createDirectories(storage.modsDirectory());
		Path source = storage.modsDirectory().resolve("local.jar");
		byte[] bytes = "local-mod".getBytes(StandardCharsets.UTF_8);
		Files.write(source, bytes);
		String hash = HashUtils.getHash(source);
		Conflict conflict = new Conflict("abc1234", "a".repeat(40), Set.of("sodium"), "mods/local.jar", hash, bytes.length,
				"mods/server.jar", "b".repeat(40), 12, ConflictAction.QUARANTINE);

		QuarantineArchive.archive(storage, "c".repeat(40), conflict);
		Path payload = storage.quarantinePayload("abc1234", conflict.conflictId());
		assertFalse(Files.exists(source));
		assertTrue(Files.exists(payload));
		Files.copy(payload, source, StandardCopyOption.REPLACE_EXISTING);
		QuarantineArchive.archive(storage, "c".repeat(40), conflict);

		assertFalse(Files.exists(source));
		assertEquals(hash, HashUtils.getHash(payload));
	}

	@Test
	void rejectsTamperedQuarantineMetadata() throws Exception {
		ClientStorage storage = ClientStorage.fromGameDirectory(temporaryDirectory.resolve("game"));
		storage.ensureRoots();
		Files.createDirectories(storage.modsDirectory());
		Path source = Files.writeString(storage.modsDirectory().resolve("local.jar"), "local-mod", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		Conflict conflict = new Conflict("abc1234", "d".repeat(40), Set.of("sodium"), "mods/local.jar", hash, Files.size(source),
				"mods/server.jar", "e".repeat(40), 12, ConflictAction.QUARANTINE);
		QuarantineArchive.archive(storage, "f".repeat(40), conflict);

		ClientStorageJsons.ClientQuarantineFields fields = ConfigTools.read(storage.quarantineManifest("abc1234"), ClientStorageJsons.ClientQuarantineFields.class).orElseThrow();
		fields.entries.get(0).sourceHash = "0".repeat(40);
		ConfigTools.writeAtomic(storage.quarantineManifest("abc1234"), fields);

		assertThrows(IOException.class, () -> QuarantineArchive.read(storage, "abc1234"));
	}

	@Test
	void restoresOnlyWhenThePackIsActiveAndTheSourcePathIsUnowned() throws Exception {
		ClientStorage storage = storage();
		Conflict conflict = archiveConflict(storage, "mods/local.jar", "local-mod");
		installActiveRecord(storage, "mods/server.jar");

		QuarantineArchive.restore(storage, conflict.modpackId(), conflict.conflictId());

		assertEquals("local-mod", Files.readString(storage.gamePath(conflict.sourcePath()), StandardCharsets.UTF_8));
		assertTrue(QuarantineArchive.read(storage, conflict.modpackId()).entries.isEmpty());
		assertFalse(Files.exists(storage.quarantinePayload(conflict.modpackId(), conflict.conflictId())));
	}

	@Test
	void refusesRestoreWhenTheActiveGenerationStillOwnsTheSourcePath() throws Exception {
		ClientStorage storage = storage();
		Conflict conflict = archiveConflict(storage, "mods/local.jar", "local-mod");
		installActiveRecord(storage, conflict.sourcePath());

		assertThrows(IOException.class, () -> QuarantineArchive.restore(storage, conflict.modpackId(), conflict.conflictId()));
		assertTrue(QuarantineArchive.read(storage, conflict.modpackId()).entries.stream().anyMatch(entry -> conflict.conflictId().equals(entry.conflictId)));
		assertTrue(Files.exists(storage.gamePath(conflict.sourcePath())));
	}

	@Test
	void refusesRestoreWithoutTheSameActivePack() throws Exception {
		ClientStorage storage = storage();
		Conflict conflict = archiveConflict(storage, "mods/local.jar", "local-mod");

		assertThrows(IOException.class, () -> QuarantineArchive.restore(storage, conflict.modpackId(), conflict.conflictId()));
		assertTrue(QuarantineArchive.read(storage, conflict.modpackId()).entries.stream().anyMatch(entry -> conflict.conflictId().equals(entry.conflictId)));
	}

	@Test
	void refusesToOverwriteDifferentDestinationBytes() throws Exception {
		ClientStorage storage = storage();
		Conflict conflict = archiveConflict(storage, "mods/local.jar", "local-mod");
		installActiveRecord(storage, "mods/server.jar");
		Files.writeString(storage.gamePath(conflict.sourcePath()), "different", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> QuarantineArchive.restore(storage, conflict.modpackId(), conflict.conflictId()));
		assertEquals("different", Files.readString(storage.gamePath(conflict.sourcePath()), StandardCharsets.UTF_8));
		assertEquals(1, QuarantineArchive.read(storage, conflict.modpackId()).entries.size());
	}

	@Test
	void refusesTamperedPayloadBeforeAnyRestoreMutation() throws Exception {
		ClientStorage storage = storage();
		Conflict conflict = archiveConflict(storage, "mods/local.jar", "local-mod");
		installActiveRecord(storage, "mods/server.jar");
		Files.writeString(storage.quarantinePayload(conflict.modpackId(), conflict.conflictId()), "tampered", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> QuarantineArchive.restore(storage, conflict.modpackId(), conflict.conflictId()));
		assertFalse(Files.exists(storage.gamePath(conflict.sourcePath())));
		assertThrows(IOException.class, () -> QuarantineArchive.read(storage, conflict.modpackId()));
	}

	@Test
	void treatsAnAlreadyRestoredExactDestinationAsIdempotent() throws Exception {
		ClientStorage storage = storage();
		Conflict conflict = archiveConflict(storage, "mods/local.jar", "local-mod");
		installActiveRecord(storage, "mods/server.jar");
		Files.writeString(storage.gamePath(conflict.sourcePath()), "local-mod", StandardCharsets.UTF_8);

		QuarantineArchive.restore(storage, conflict.modpackId(), conflict.conflictId());

		assertEquals("local-mod", Files.readString(storage.gamePath(conflict.sourcePath()), StandardCharsets.UTF_8));
		assertTrue(QuarantineArchive.read(storage, conflict.modpackId()).entries.isEmpty());
	}

	private ClientStorage storage() throws IOException {
		ClientStorage storage = ClientStorage.fromGameDirectory(temporaryDirectory.resolve("game"));
		storage.ensureRoots();
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private Conflict archiveConflict(ClientStorage storage, String sourcePath, String contents) throws Exception {
		Path source = storage.gamePath(sourcePath);
		Files.writeString(source, contents, StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		Conflict conflict = new Conflict("abc1234", "a".repeat(40), Set.of("sodium"), sourcePath, hash, Files.size(source), "mods/server.jar", "b".repeat(40), 12,
				ConflictAction.QUARANTINE);
		QuarantineArchive.archive(storage, "c".repeat(40), conflict);
		return conflict;
	}

	private void installActiveRecord(ClientStorage storage, String path) throws Exception {
		byte[] bytes = "active-mod".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(Files.write(storage.gamePath(path), bytes));
		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.modpackName = "Test";
		Jsons.CompleteModpackContentFields.ModpackGroupFields group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		Jsons.CompleteModpackContentFields.GroupFileFields file = new Jsons.CompleteModpackContentFields.GroupFileFields();
		file.size = String.valueOf(bytes.length);
		file.type = "mod";
		file.sha1 = hash;
		file.murmur = "0";
		group.files = Map.of(path, file);
		fields.groups = Map.of("main", group);
		GenerationRecord record = GenerationRecord.create(GroupManifestValidator.validate(fields), null, Instant.parse("2026-01-01T00:00:00Z"), "");
		new ClientGenerationStore(storage).write(record);
		storage.writeActiveState(record.manifest().modpackId(), record.metadata().generationId());
	}
}
