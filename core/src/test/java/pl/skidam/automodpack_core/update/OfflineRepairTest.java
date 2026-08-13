package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;

class OfflineRepairTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void rebuildsCorruptCasFromVerifiedProjectionBeforeRepairingMaterialization() throws Exception {
		ClientStorage storage = storage();
		byte[] expectedBytes = "server-default".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(expectedBytes);
		SelectedModpackTarget target = install(storage, new FileSpec("config/settings.json", "config", false, hash, expectedBytes.length));
		write(storage.activePath("config/settings.json"), expectedBytes);
		write(storage.objectsDirectory().resolve(hash), "broken-object".getBytes(StandardCharsets.UTF_8));
		write(storage.gamePath("config/settings.json"), "broken-live".getBytes(StandardCharsets.UTF_8));
		OfflineRepair repair = new OfflineRepair(storage);

		OfflineRepair.Prepared before = repair.inspect(new OfflineRepair.Request(target, Set.of(), null));
		OfflineRepair.Receipt receipt = repair.apply(before);

		assertEquals(2, before.findings().size());
		assertEquals(1, receipt.repairedCasObjects());
		assertEquals(1, receipt.repairedMaterializedFiles());
		assertTrue(receipt.complete());
		assertTrue(FileIntegrity.matches(storage.objectsDirectory().resolve(hash), expectedBytes.length, hash));
		assertTrue(FileIntegrity.matches(storage.gamePath("config/settings.json"), expectedBytes.length, hash));
	}

	@Test
	void reportsUnavailableBytesWithoutChangingDamagedFiles() throws Exception {
		ClientStorage storage = storage();
		byte[] expectedBytes = "unavailable".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(expectedBytes);
		SelectedModpackTarget target = install(storage, new FileSpec("config/settings.json", "config", false, hash, expectedBytes.length));
		byte[] corrupt = "damaged".getBytes(StandardCharsets.UTF_8);
		write(storage.objectsDirectory().resolve(hash), corrupt);
		OfflineRepair repair = new OfflineRepair(storage);

		OfflineRepair.Prepared before = repair.inspect(new OfflineRepair.Request(target, Set.of(), null));
		OfflineRepair.Receipt receipt = repair.apply(before);

		assertTrue(before.requiresUpdate());
		assertEquals(0, receipt.repairedCasObjects());
		assertEquals(0, receipt.repairedMaterializedFiles());
		assertFalse(receipt.complete());
		assertEquals("damaged", Files.readString(storage.objectsDirectory().resolve(hash), StandardCharsets.UTF_8));
	}

	@Test
	void treatsEditableDifferenceAsResetCandidateWithoutResettingIt() throws Exception {
		ClientStorage storage = storage();
		byte[] expectedBytes = "server-default".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(expectedBytes);
		SelectedModpackTarget target = install(storage, new FileSpec("config/settings.json", "config", true, hash, expectedBytes.length));
		write(storage.objectsDirectory().resolve(hash), expectedBytes);
		write(storage.activePath("config/settings.json"), expectedBytes);
		Path live = storage.gamePath("config/settings.json");
		write(live, "my-local-edit".getBytes(StandardCharsets.UTF_8));
		OfflineRepair repair = new OfflineRepair(storage);

		OfflineRepair.Prepared prepared = repair.inspect(new OfflineRepair.Request(target, Set.of(), null));
		OfflineRepair.Receipt receipt = repair.apply(prepared);

		assertTrue(prepared.healthy());
		assertEquals(Set.of("config/settings.json"), prepared.editableResetCandidates().stream().map(OfflineRepair.EditableResetCandidate::logicalPath).collect(Collectors.toSet()));
		assertEquals("my-local-edit", Files.readString(live, StandardCharsets.UTF_8));
		assertEquals(0, receipt.repairedMaterializedFiles());
	}

	@Test
	void appliesOnlySelectedEditableResetsAndUnownedModArchival() throws Exception {
		ClientStorage storage = storage();
		byte[] expectedBytes = "server-default".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(expectedBytes);
		SelectedModpackTarget target = install(storage, new FileSpec("config/settings.json", "config", true, hash, expectedBytes.length));
		write(storage.objectsDirectory().resolve(hash), expectedBytes);
		write(storage.activePath("config/settings.json"), expectedBytes);
		Path live = write(storage.gamePath("config/settings.json"), "my-local-edit".getBytes(StandardCharsets.UTF_8));
		Path protectedJar = write(storage.modsDirectory().resolve("automodpack.jar"), "self".getBytes(StandardCharsets.UTF_8));
		Path extra = write(storage.modsDirectory().resolve("extra.jar"), "extra".getBytes(StandardCharsets.UTF_8));
		OfflineRepair repair = new OfflineRepair(storage);

		OfflineRepair.Prepared prepared = repair.inspect(new OfflineRepair.Request(target, Set.of(), protectedJar));
		OfflineRepair.Receipt receipt = repair.apply(prepared, Set.of("config/settings.json"), Set.of("mods/extra.jar"));

		assertEquals(1, receipt.resetEditableFiles());
		assertEquals(1, receipt.archivedUnownedMods());
		assertTrue(FileIntegrity.matches(live, expectedBytes.length, hash));
		assertFalse(Files.exists(extra));
		assertTrue(Files.exists(protectedJar));
		Set<PreservationVault.Reason> reasons = PreservationVault.read(storage, target.manifest().modpackId()).claims().stream()
				.map(PreservationVault.Claim::reason).collect(Collectors.toSet());
		assertEquals(Set.of(PreservationVault.Reason.EDITABLE_RESET, PreservationVault.Reason.STRICT_REPAIR), reasons);
	}

	@Test
	void resumesJournaledRepairAfterPowerLoss() throws Exception {
		ClientStorage storage = storage();
		byte[] expectedBytes = "server-default".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(expectedBytes);
		SelectedModpackTarget target = install(storage, new FileSpec("config/settings.json", "config", true, hash, expectedBytes.length));
		write(storage.objectsDirectory().resolve(hash), expectedBytes);
		write(storage.activePath("config/settings.json"), expectedBytes);
		byte[] editedBytes = "my-local-edit".getBytes(StandardCharsets.UTF_8);
		Path live = write(storage.gamePath("config/settings.json"), editedBytes);
		Path extra = write(storage.modsDirectory().resolve("extra.jar"), "extra".getBytes(StandardCharsets.UTF_8));
		OfflineRepair repair = new OfflineRepair(storage);
		OfflineRepair.Request request = new OfflineRepair.Request(target, Set.of(), null);
		OfflineRepair.Prepared prepared = repair.inspect(request);
		OfflineRepair.EditableResetCandidate candidate = prepared.editableResetCandidates().get(0);
		ClientStorageJsons.OfflineRepairJournalFields journal = new ClientStorageJsons.OfflineRepairJournalFields();
		journal.modpackId = prepared.modpackId();
		journal.generationId = prepared.generationId();
		journal.selectionDigest = prepared.selectionDigest();
		ClientStorageJsons.OfflineRepairJournalFields.EditableResetFields reset = new ClientStorageJsons.OfflineRepairJournalFields.EditableResetFields();
		reset.logicalPath = candidate.logicalPath();
		reset.defaultHash = candidate.defaultHash();
		reset.defaultSize = candidate.defaultSize();
		reset.currentHash = candidate.currentHash();
		reset.currentSize = candidate.currentSize();
		reset.absent = candidate.absent();
		journal.editableResets = List.of(reset);
		ClientStorageJsons.OfflineRepairJournalFields.UnownedModFields unowned = new ClientStorageJsons.OfflineRepairJournalFields.UnownedModFields();
		unowned.logicalPath = "mods/extra.jar";
		unowned.objectHash = HashUtils.getHash(extra);
		unowned.size = Files.size(extra);
		journal.unownedMods = List.of(unowned);
		ConfigTools.writeAtomic(storage.repairJournalFile(), journal);

		OfflineRepair.Receipt receipt = repair.recover(request);

		assertTrue(FileIntegrity.matches(live, expectedBytes.length, hash));
		assertFalse(Files.exists(extra));
		assertFalse(Files.exists(storage.repairJournalFile()));
		assertEquals(1, receipt.resetEditableFiles());
		assertEquals(1, receipt.archivedUnownedMods());
	}

	@Test
	void reportsUnownedModsButProtectsOnlyTheExactLoadedJar() throws Exception {
		ClientStorage storage = storage();
		byte[] expectedBytes = "server-mod".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(expectedBytes);
		SelectedModpackTarget target = install(storage, new FileSpec("mods/server.jar", "mod", false, hash, expectedBytes.length));
		write(storage.objectsDirectory().resolve(hash), expectedBytes);
		write(storage.activePath("mods/server.jar"), expectedBytes);
		Path protectedJar = write(storage.modsDirectory().resolve("automodpack.jar"), "self".getBytes(StandardCharsets.UTF_8));
		write(storage.modsDirectory().resolve("extra.jar"), "extra".getBytes(StandardCharsets.UTF_8));
		OfflineRepair repair = new OfflineRepair(storage);

		OfflineRepair.Prepared prepared = repair.inspect(new OfflineRepair.Request(target, Set.of(), protectedJar));

		assertEquals(List.of("mods/extra.jar"), prepared.unownedModPaths());
		assertTrue(prepared.healthy());
	}

	@Test
	void verifiesAndRepairsDurablePreservationClaims() throws Exception {
		ClientStorage storage = storage();
		byte[] targetBytes = "target".getBytes(StandardCharsets.UTF_8);
		String targetHash = HashUtils.sha1(targetBytes);
		SelectedModpackTarget target = install(storage, new FileSpec("config/target.json", "config", false, targetHash, targetBytes.length));
		write(storage.objectsDirectory().resolve(targetHash), targetBytes);
		write(storage.activePath("config/target.json"), targetBytes);
		write(storage.gamePath("config/target.json"), targetBytes);
		byte[] preservedBytes = "preserved".getBytes(StandardCharsets.UTF_8);
		String preservedHash = HashUtils.sha1(preservedBytes);
		Path preservedSource = write(storage.gamePath("config/removed.json"), preservedBytes);
		PreservationVault.preserve(storage, target.manifest().modpackId(), target.generationTarget().targetGenerationId(), PreservationVault.Reason.SERVER_REMOVAL, Root.GAME_DIR,
				"config/removed.json", preservedHash, preservedBytes.length);
		write(storage.objectsDirectory().resolve(preservedHash), "corrupt".getBytes(StandardCharsets.UTF_8));
		OfflineRepair repair = new OfflineRepair(storage);

		OfflineRepair.Prepared before = repair.inspect(new OfflineRepair.Request(target, Set.of(), null));
		OfflineRepair.Receipt receipt = repair.apply(before);

		assertTrue(before.findings().stream().anyMatch(finding -> finding.place() == OfflineRepair.Place.CAS && finding.expectedHash().equals(preservedHash) && finding.locallyRepairable()));
		assertTrue(receipt.complete());
		assertTrue(FileIntegrity.matches(storage.objectsDirectory().resolve(preservedHash), preservedBytes.length, preservedHash));
		assertTrue(FileIntegrity.matches(preservedSource, preservedBytes.length, preservedHash));
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = ClientStorage.open(temporaryDirectory.resolve("game"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static SelectedModpackTarget install(ClientStorage storage, FileSpec... specs) throws Exception {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
		for (FileSpec spec : specs) files.put(spec.path(), new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(spec.size()), spec.type(), spec.editable(), false, spec.hash(), "0"));
		group.files = files;
		fields.groups = Map.of("main", group);
		GenerationRecord record = GenerationRecord.create(GroupManifestValidator.validate(fields), null, Instant.parse("2026-01-01T00:00:00Z"), "");
		SelectedModpackTarget target = SelectedModpackTarget.prepare(record.toFields(), null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		new ClientGenerationStore(storage).write(record);
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(record.manifest().modpackId(), null, target.selection().intent());
		storage.writeActiveState(record.manifest().modpackId(), record.metadata().generationId());
		return target;
	}

	private static Path write(Path path, byte[] bytes) throws Exception {
		Files.createDirectories(path.getParent());
		return Files.write(path, bytes);
	}

	private record FileSpec(String path, String type, boolean editable, String hash, long size) {}
}
