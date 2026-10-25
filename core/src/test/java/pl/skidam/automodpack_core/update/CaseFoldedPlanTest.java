package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.update.UpdatePlan.*;

/**
 * A rename between generations whose two spellings a filesystem may or may not resolve to one file. Only the
 * observation decides: spellings proven to be one file get one decision, any other spellings are two files.
 */
class CaseFoldedPlanTest {
	private static final String BEFORE = "config/Foo.toml";
	private static final String AFTER = "config/foo.toml";
	private static final String OLD = "2".repeat(40);
	private static final String NEW = "1".repeat(40);

	@Test
	void aCaseOnlyRenameReplacesTheOneFileInPlace() {
		UpdatePlan plan = plan(NEW, true);

		assertEquals(List.of(new Operation(Root.GAME_DIR, AFTER, OperationType.INSTALL_OBJECT, NEW, 9, OLD)), gameOperations(plan));
		assertEquals(List.of(new ProjectedFile(Root.GAME_DIR, AFTER, true, NEW, 9)), rows(plan, Root.GAME_DIR));
		// The projection is rebuilt whole from its present rows, so its old spelling simply retires.
		assertEquals(List.of(new ProjectedFile(Root.PROJECTION, BEFORE, false, null, -1), new ProjectedFile(Root.PROJECTION, AFTER, true, NEW, 9)), rows(plan, Root.PROJECTION));
		assertEquals(List.of(), plan.preservations());
	}

	@Test
	void aCaseOnlyRenameOfTheSameBytesKeepsTheFile() {
		UpdatePlan plan = plan(OLD, true);

		assertEquals(List.of(new Operation(Root.GAME_DIR, AFTER, OperationType.INSTALL_OBJECT, OLD, 9, OLD)), gameOperations(plan));
		assertEquals(List.of(new ProjectedFile(Root.GAME_DIR, AFTER, true, OLD, 9)), rows(plan, Root.GAME_DIR));
	}

	@Test
	void aCaseSensitiveFilesystemMovesTheFileToItsNewName() {
		UpdatePlan plan = plan(NEW, false);

		assertEquals(List.of(new Operation(Root.GAME_DIR, AFTER, OperationType.INSTALL_OBJECT, NEW, 9, null), new Operation(Root.GAME_DIR, BEFORE, OperationType.DELETE, null, -1, OLD)),
				gameOperations(plan));
		assertEquals(List.of(new ProjectedFile(Root.GAME_DIR, BEFORE, false, null, -1), new ProjectedFile(Root.GAME_DIR, AFTER, true, NEW, 9)), rows(plan, Root.GAME_DIR));
		assertEquals(List.of(new Preservation(Root.GAME_DIR, BEFORE, OLD, 9, PreservationProof.ACTIVE_LEDGER)), plan.preservations());
	}

	@Test
	void aNormalizationOnlyRenameOnAVolumeThatKeepsBothNamesApartStillRetiresTheOldName() {
		// NTFS, exFAT and SMB to Windows fold case but not Unicode normalization: the NFD spelling stats absent.
		UpdatePlan plan = plan("config/caf\u00e9.toml", "config/cafe\u0301.toml", NEW, false);

		assertTrue(gameOperations(plan).stream().anyMatch(operation -> operation.operation() == OperationType.DELETE && operation.relativePath().equals("config/caf\u00e9.toml")),
				"the old NFC file must be deleted, or the install's expected-existing check fails on the absent NFD name: " + gameOperations(plan));
	}

	@Test
	void aCaseOnlyRenameInADirectoryThatTellsCasesApartStillRetiresTheOldName() {
		// The probe answered for automodpack/client; a per-directory case-sensitive config/ (WSL-made NTFS dir, ext4 +F off) disagrees.
		UpdatePlan plan = plan(BEFORE, AFTER, NEW, false);

		assertTrue(gameOperations(plan).stream().anyMatch(operation -> operation.operation() == OperationType.DELETE && operation.relativePath().equals(BEFORE)),
				"the observation shows two files, so the plan must not treat them as one: " + gameOperations(plan));
	}

	/** Greek capital sigma lowercases to a final or a medial sigma by context; folding disks treat all three as one letter. */
	@Test
	void aGreekCaseOnlyRenameOnACaseInsensitiveFilesystemNeverDeletesTheFile() {
		UpdatePlan plan = plan("config/\u039F\u0394\u039F\u03A3/a.toml", "config/\u03BF\u03B4\u03BF\u03C3/a.toml", NEW, true);

		assertEquals(List.of(), gameOperations(plan).stream().filter(operation -> operation.operation() == OperationType.DELETE).toList(),
				"deleting the old spelling deletes the one file both spellings name");
	}

	/** Case-sensitive APFS still joins Unicode normalizations: NFC and NFD name one file there. */
	@Test
	void aNormalizationOnlyRenameOnCaseSensitiveApfsNeverDeletesTheFile() {
		UpdatePlan plan = plan("config/caf\u00e9.toml", "config/cafe\u0301.toml", NEW, true);

		assertEquals(List.of(), gameOperations(plan).stream().filter(operation -> operation.operation() == OperationType.DELETE).toList(),
				"deleting the old spelling deletes the one file both spellings name");
	}

	/** NTFS is case-insensitive but normalization-sensitive: an NFD player file and the pack's NFC file are two files. */
	@Test
	void anNfcPackFileNextToThePlayersNfdFileOnNtfsIsANewFile() {
		ModpackJsons.ModpackContentFields target = manifest(item("config/caf\u00e9.toml", NEW, 9), entry("config/caf\u00e9.toml", NEW, OwnershipLedger.Status.PRESENT));
		Map<FileKey, FileState> files = new TreeMap<>(FileKey.ORDER);
		files.put(new FileKey(Root.GAME_DIR, "config/cafe\u0301.toml"), new FileState(OLD, 9, true));
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3(), Map.of(), UpdatePlanner.Owed.NOTHING, Map.of()));

		assertEquals(List.of(new Operation(Root.GAME_DIR, "config/caf\u00e9.toml", OperationType.INSTALL_OBJECT, NEW, 9, null)), gameOperations(plan),
				"the NFC path holds nothing on NTFS, so the install must not expect the player's NFD file there");
	}

	/** After a case-only rename of the same bytes the ledger names the one file under both spellings; the old one must not delete what the player gets back. */
	@Test
	void aRemovalAfterACaseOnlyRenameGivesThePlayersFileBackUnderEitherSpelling() {
		String player = "3".repeat(40);
		ModpackJsons.ModpackContentFields installed = manifest(item(AFTER, OLD, 9), entry(BEFORE, OLD, OwnershipLedger.Status.TOMBSTONE),
				entry(AFTER, OLD, OwnershipLedger.Status.PRESENT));
		Map<FileKey, FileState> files = new TreeMap<>(FileKey.ORDER);
		files.put(new FileKey(Root.PROJECTION, AFTER), new FileState(OLD, 9, true));
		files.put(new FileKey(Root.GAME_DIR, BEFORE), new FileState(OLD, 9, true));
		files.put(new FileKey(Root.GAME_DIR, AFTER), new FileState(OLD, 9, true));
		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, Map.of(AFTER, new InstanceTree.TrackedFile(Root.GAME_DIR, "", AFTER, player, 9)),
				files, Set.of(player), null, new ClientConfigJsons.ClientConfigFieldsV3(), UpdatePlanner.Owed.NOTHING,
				Map.of(new FileKey(Root.GAME_DIR, AFTER), new FileKey(Root.GAME_DIR, BEFORE))));

		assertEquals(List.of(new ProjectedFile(Root.GAME_DIR, BEFORE, true, player, 9)), rows(plan, Root.GAME_DIR));
	}

	private static UpdatePlan plan(String targetHash, boolean bothSpellingsStat) {
		return plan(BEFORE, AFTER, targetHash, bothSpellingsStat);
	}

	/** {@code oneFile}: both spellings stat, and the filesystem resolves them to the same file. */
	private static UpdatePlan plan(String BEFORE, String AFTER, String targetHash, boolean oneFile) {
		ModpackJsons.ModpackContentFields installed = manifest(item(BEFORE, OLD, 9), entry(BEFORE, OLD, OwnershipLedger.Status.PRESENT));
		ModpackJsons.ModpackContentFields target = manifest(item(AFTER, targetHash, 9), entry(BEFORE, OLD, OwnershipLedger.Status.TOMBSTONE),
				entry(AFTER, targetHash, OwnershipLedger.Status.PRESENT));
		Map<FileKey, FileState> files = new TreeMap<>(FileKey.ORDER);
		files.put(new FileKey(Root.PROJECTION, BEFORE), new FileState(OLD, 9, true));
		files.put(new FileKey(Root.GAME_DIR, BEFORE), new FileState(OLD, 9, true));
		if (oneFile) files.put(new FileKey(Root.GAME_DIR, AFTER), new FileState(OLD, 9, true));
		Map<FileKey, FileKey> sameFiles = oneFile ? Map.of(new FileKey(Root.GAME_DIR, AFTER), new FileKey(Root.GAME_DIR, BEFORE)) : Map.of();
		return UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3(), Map.of(), UpdatePlanner.Owed.NOTHING, sameFiles));
	}

	private static List<Operation> gameOperations(UpdatePlan plan) {
		return plan.operations().stream().filter(operation -> operation.root() == Root.GAME_DIR).toList();
	}

	private static List<ProjectedFile> rows(UpdatePlan plan, Root root) {
		return plan.projectedFinalState().stream().filter(file -> file.root() == root).toList();
	}

	private static ModpackJsons.ModpackContentFields manifest(ModpackJsons.ModpackContentFields.ModpackContentItem item, OwnershipLedger.Entry... entries) {
		ModpackJsons.ModpackContentFields manifest = new ModpackJsons.ModpackContentFields(new LinkedHashSet<>(List.of(item)));
		manifest.modpackId = "abc1234";
		manifest.contentToken = "1".repeat(40);
		manifest.policySha1 = "2".repeat(40);
		Map<String, OwnershipLedger.Entry> ledger = new TreeMap<>();
		for (OwnershipLedger.Entry entry : entries) ledger.put(entry.logicalPath(), entry);
		manifest.ownershipLedger = new OwnershipLedger("abc1234", ledger).toFields();
		return manifest;
	}

	private static ModpackJsons.ModpackContentFields.ModpackContentItem item(String path, String hash, long size) {
		return new ModpackJsons.ModpackContentFields.ModpackContentItem(path, size, false, hash, "0");
	}

	private static OwnershipLedger.Entry entry(String path, String hash, OwnershipLedger.Status status) {
		TreeSet<OwnershipLedger.Content> hashes = new TreeSet<>(Comparator.comparing(OwnershipLedger.Content::sha1).thenComparingLong(OwnershipLedger.Content::size));
		hashes.add(new OwnershipLedger.Content(hash, 9));
		return new OwnershipLedger.Entry(path, hashes, new TreeSet<>(Set.of("main")), status);
	}
}
