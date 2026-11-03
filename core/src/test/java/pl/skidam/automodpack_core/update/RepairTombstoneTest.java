package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The repair action applies every tombstone of the active pack, as a first install does, with the bytes kept on the timeline. */
class RepairTombstoneTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void repairCleansALongDeletedConfigThatUpdatesLeaveAlone() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		byte[] kept = "kept".getBytes(StandardCharsets.UTF_8);
		byte[] old = "old-config".getBytes(StandardCharsets.UTF_8);
		byte[] crash = "crash".getBytes(StandardCharsets.UTF_8);
		Map<String, byte[]> earlier = new LinkedHashMap<>();
		earlier.put("config/kept.toml", kept);
		earlier.put("config/old.toml", old);
		earlier.put("crash-reports/crash.txt", crash);
		OwnershipLedger parent = TestPacks.document(manifest(earlier)).ownershipLedger();
		PackDocument document = TestPacks.document(manifest(Map.of("config/kept.toml", kept)), parent, TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(document, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		new ClientSelectionStore(storage.selectionFile()).put(document.manifest().modpackId(), target.selection().intent());
		storage.writeActiveState(document);
		write(storage.activePath("config/kept.toml"), kept);
		write(storage.gamePath("config/kept.toml"), kept);
		Path oldConfig = write(storage.gamePath("config/old.toml"), old);
		Path crashReport = write(storage.gamePath("crash-reports/crash.txt"), crash);
		OfflineRepair repair = new OfflineRepair(storage);

		OfflineRepair.Prepared before = repair.inspect(new OfflineRepair.Request(target, Set.of(), null, Set.of()));
		OfflineRepair.Receipt receipt = repair.apply(before, Set.of(), Set.of());

		assertEquals(List.of("config/old.toml"), before.ledgerTombstonePaths());
		assertEquals(1, receipt.archivedUnownedMods());
		assertFalse(Files.exists(oldConfig));
		assertTrue(Files.exists(crashReport));
		assertTrue(FileIntegrity.matches(storage.objectFile(HashUtils.sha1(old)), old.length, HashUtils.sha1(old)), "the deleted config stays recoverable");
		assertTrue(receipt.after().ledgerTombstonePaths().isEmpty());
	}

	/** A case-only rename leaves a tombstone that folds onto the shipped path; repair never treats it as a file to delete, on any disk. */
	@Test
	void repairNeverDeletesThroughATombstoneThatFoldsOntoAShippedPath() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		byte[] bytes = "same-bytes".getBytes(StandardCharsets.UTF_8);
		OwnershipLedger parent = TestPacks.document(manifest(Map.of("config/Foo.toml", bytes))).ownershipLedger();
		PackDocument document = TestPacks.document(manifest(Map.of("config/foo.toml", bytes)), parent, TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(document, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		new ClientSelectionStore(storage.selectionFile()).put(document.manifest().modpackId(), target.selection().intent());
		storage.writeActiveState(document);
		write(storage.activePath("config/foo.toml"), bytes);
		Path shipped = write(storage.gamePath("config/foo.toml"), bytes);
		Path otherSpelling = storage.gamePath("config/Foo.toml");
		if (!Files.exists(otherSpelling)) write(otherSpelling, bytes);

		OfflineRepair repair = new OfflineRepair(storage);
		OfflineRepair.Prepared before = repair.inspect(new OfflineRepair.Request(target, Set.of(), null, Set.of()));
		repair.apply(before, Set.of(), Set.of());

		assertEquals(List.of(), before.ledgerTombstonePaths());
		assertTrue(Files.exists(shipped));
	}

	/** On a case-insensitive disk (NTFS, default APFS) a case-only rename names one file under both spellings: repair must not delete what the pack ships. */
	@Test
	void repairKeepsTheShippedFileAfterACaseOnlyRenameOnACaseInsensitiveDisk() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		byte[] bytes = "same-bytes".getBytes(StandardCharsets.UTF_8);
		OwnershipLedger parent = TestPacks.document(manifest(Map.of("config/Foo.toml", bytes))).ownershipLedger();
		PackDocument document = TestPacks.document(manifest(Map.of("config/foo.toml", bytes)), parent, TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(document, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		new ClientSelectionStore(storage.selectionFile()).put(document.manifest().modpackId(), target.selection().intent());
		storage.writeActiveState(document);
		write(storage.activePath("config/foo.toml"), bytes);
		Path shipped = write(storage.gamePath("config/foo.toml"), bytes);
		assumeTrue(Files.exists(storage.gamePath("config/Foo.toml")), "needs a case-insensitive filesystem");

		OfflineRepair repair = new OfflineRepair(storage);
		OfflineRepair.Prepared before = repair.inspect(new OfflineRepair.Request(target, Set.of(), null, Set.of()));
		repair.apply(before, Set.of(), Set.of());

		assertEquals(List.of(), before.ledgerTombstonePaths(), "the tombstoned spelling is the shipped file");
		assertTrue(Files.exists(shipped));
	}

	private static GroupManifest manifest(Map<String, byte[]> contents) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		group.defaultSelected = true;
		Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
		contents.forEach((path, bytes) -> files.put(path, new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(bytes.length), false, HashUtils.sha1(bytes), "0")));
		group.files = files;
		fields.categories = Map.of("General", Map.of("main", group));
		return GroupManifestValidator.validate(fields);
	}

	private static Path write(Path path, byte[] bytes) throws Exception {
		Files.createDirectories(path.getParent());
		return Files.write(path, bytes);
	}
}
