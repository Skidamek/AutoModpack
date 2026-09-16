package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.update.PreservationVault.Reason;
import pl.skidam.automodpack_core.update.PreservationVault.Status;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.ConflictAction;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;

class PreservationVaultTest {
	private static final String MODPACK_ID = "abc1234";
	private static final String GENERATION_ID = "c".repeat(40);

	@TempDir
	Path temporaryDirectory;

	@Test
	void conflictIsClaimedInCasBeforeRemovalAndRetryIsIdempotent() throws Exception {
		ClientStorage storage = storage();
		Path source = Files.writeString(storage.gamePath("mods/local.jar"), "local-mod", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		Conflict conflict = new Conflict(MODPACK_ID, "a".repeat(40), Set.of("sodium"), "mods/local.jar", hash, Files.size(source), "mods/server.jar", "b".repeat(40), 12,
				ConflictAction.PRESERVE_LOCAL);

		PreservationVault.Claim first = PreservationVault.preserveConflict(storage, GENERATION_ID, conflict);
		PreservationVault.Claim repeated = PreservationVault.preserveConflict(storage, GENERATION_ID, conflict);

		assertEquals(first, repeated);
		assertEquals(Reason.LOCAL_CONFLICT, first.reason());
		assertFalse(Files.exists(source));
		assertEquals(hash, HashUtils.getHash(storage.objectsDirectory().resolve(hash)));
		assertEquals(1, PreservationVault.read(storage, MODPACK_ID).claims().size());
	}

	@Test
	void originalRestoreRequiresAnActiveUnownedPathAndKeepsTheClaim() throws Exception {
		ClientStorage storage = storage();
		Path source = Files.writeString(storage.gamePath("mods/local.jar"), "local-mod", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		PreservationVault.Claim claim = PreservationVault.preserve(storage, MODPACK_ID, GENERATION_ID, Reason.LOCAL_CONFLICT, Root.GAME_DIR, "mods/local.jar", hash,
				Files.size(source), Instant.parse("2026-08-12T10:00:00Z"));
		Files.delete(source);

		assertThrows(IOException.class, () -> PreservationVault.restoreOriginal(storage, MODPACK_ID, claim.claimId()));
		installActiveRecord(storage, "mods/server.jar");
		Path restored = PreservationVault.restoreOriginal(storage, MODPACK_ID, claim.claimId());

		assertEquals("local-mod", Files.readString(restored, StandardCharsets.UTF_8));
		PreservationVault.Claim retained = PreservationVault.read(storage, MODPACK_ID).claims().get(0);
		assertEquals(Status.RESTORED, retained.status());
		assertTrue(Files.exists(storage.objectsDirectory().resolve(hash)));
	}

	@Test
	void saveCopyUsesAStablePathAndNeverOverwritesDifferentBytes() throws Exception {
		ClientStorage storage = storage();
		Path source = Files.writeString(storage.gamePath("config/local.txt"), "local", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		PreservationVault.Claim claim = PreservationVault.preserve(storage, MODPACK_ID, GENERATION_ID, Reason.SERVER_REMOVAL, Root.GAME_DIR, "config/local.txt", hash,
				Files.size(source));

		Path first = PreservationVault.saveCopy(storage, MODPACK_ID, claim.claimId());
		Path repeated = PreservationVault.saveCopy(storage, MODPACK_ID, claim.claimId());
		assertEquals(first, repeated);
		Files.writeString(first, "different", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> PreservationVault.saveCopy(storage, MODPACK_ID, claim.claimId()));
		assertEquals(1, PreservationVault.read(storage, MODPACK_ID).claims().size());
	}

	@Test
	void originalRestoreRefusesAnActiveGeneratedCopyPath() throws Exception {
		ClientStorage storage = storage();
		Path source = Files.writeString(storage.gamePath("mods/generated.jar"), "nested-mod", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		PreservationVault.Claim claim = PreservationVault.preserve(storage, MODPACK_ID, GENERATION_ID, Reason.LOCAL_CONFLICT, Root.GAME_DIR, "mods/generated.jar", hash,
				Files.size(source));
		Files.delete(source);
		installActiveRecord(storage, "mods/server.jar");
		var active = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current()).orElseThrow();
		new GeneratedCopyState(MODPACK_ID, active.generationTarget().targetGenerationId(), UpdateTransaction.digest(active.selection().intent()),
				java.util.List.of(new GeneratedCopyState.Entry("mods/generated.jar", hash, 10))).write(storage);

		assertThrows(IOException.class, () -> PreservationVault.restoreOriginal(storage, MODPACK_ID, claim.claimId()));
		assertFalse(Files.exists(source));
	}

	@Test
	void explicitDeletionReleasesOnlyTheClaim() throws Exception {
		ClientStorage storage = storage();
		Path source = Files.writeString(storage.gamePath("config/local.txt"), "local", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		PreservationVault.Claim claim = PreservationVault.preserve(storage, MODPACK_ID, GENERATION_ID, Reason.EDITABLE_RESET, Root.GAME_DIR, "config/local.txt", hash,
				Files.size(source));

		PreservationVault.delete(storage, MODPACK_ID, claim.claimId());

		assertTrue(PreservationVault.read(storage, MODPACK_ID).claims().isEmpty());
		assertTrue(Files.exists(storage.objectsDirectory().resolve(hash)));
	}

	private ClientStorage storage() throws IOException {
		ClientStorage storage = ClientStorage.fromGameDirectory(temporaryDirectory.resolve("game"));
		storage.ensureRoots();
		Files.createDirectories(storage.modsDirectory());
		Files.createDirectories(storage.gamePath("config"));
		return storage;
	}

	private void installActiveRecord(ClientStorage storage, String path) throws Exception {
		byte[] bytes = "active-mod".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(Files.write(storage.gamePath(path), bytes));
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = MODPACK_ID;
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		ModpackJsons.CompleteModpackContentFields.GroupFileFields file = new ModpackJsons.CompleteModpackContentFields.GroupFileFields();
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
