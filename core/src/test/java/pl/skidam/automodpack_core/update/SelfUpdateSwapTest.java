package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;

class SelfUpdateSwapTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void commitInstallsTheTargetJarAndRetiresTheCurrentOne() throws Exception {
		ClientStorage storage = storage();
		Path current = Files.writeString(storage.modsDirectory().resolve("automodpack-old.jar"), "old", StandardCharsets.UTF_8);
		Path target = storage.modsDirectory().resolve("automodpack-new.jar");
		byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
		String replacementHash = store(storage, replacement);
		String currentHash = HashUtils.getHash(current);

		SelfUpdateSwap.commit(storage.gameDirectory(), storage.dataLocation(), relative(storage, current), relative(storage, target), replacementHash, replacement.length, currentHash);

		assertFalse(Files.exists(current));
		assertTrue(FileIntegrity.matches(target, replacement.length, replacementHash));
		assertFalse(Files.exists(recordFile(storage)));
	}

	@Test
	void recoveryAppliesASwapInterruptedAfterStaging() throws Exception {
		ClientStorage storage = storage();
		Path current = Files.writeString(storage.modsDirectory().resolve("automodpack-old.jar"), "old", StandardCharsets.UTF_8);
		Path target = storage.modsDirectory().resolve("automodpack-new.jar");
		byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
		String replacementHash = store(storage, replacement);
		String currentHash = HashUtils.getHash(current);
		writeRecord(storage, relative(storage, current), relative(storage, target), replacementHash, replacement.length, currentHash);

		SelfUpdateSwap.recover(storage.gameDirectory(), storage.dataLocation());

		assertFalse(Files.exists(current));
		assertTrue(FileIntegrity.matches(target, replacement.length, replacementHash));
		assertFalse(Files.exists(recordFile(storage)));
		// A boot after a completed swap finds no record and changes nothing.
		SelfUpdateSwap.recover(storage.gameDirectory(), storage.dataLocation());
		assertTrue(FileIntegrity.matches(target, replacement.length, replacementHash));
	}

	@Test
	void rejectsTargetsOutsideTheModsDirectory() throws Exception {
		ClientStorage storage = storage();
		Path current = Files.writeString(storage.modsDirectory().resolve("automodpack-old.jar"), "old", StandardCharsets.UTF_8);
		byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
		String replacementHash = store(storage, replacement);

		assertThrows(IOException.class, () -> SelfUpdateSwap.commit(storage.gameDirectory(), storage.dataLocation(), relative(storage, current), "../outside.jar", replacementHash,
				replacement.length, HashUtils.getHash(current)));
		assertThrows(IOException.class, () -> SelfUpdateSwap.commit(storage.gameDirectory(), storage.dataLocation(), relative(storage, current), "config/automodpack-new.jar", replacementHash,
				replacement.length, HashUtils.getHash(current)));
		assertFalse(Files.exists(recordFile(storage)));
	}

	@Test
	void aSwapWhoseTargetBytesAreGoneIsSetAsideInsteadOfLoopingTheBoot() throws Exception {
		ClientStorage storage = storage();
		Path current = Files.writeString(storage.modsDirectory().resolve("automodpack-old.jar"), "old", StandardCharsets.UTF_8);
		Path target = storage.modsDirectory().resolve("automodpack-new.jar");
		byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
		String replacementHash = store(storage, replacement);
		String currentHash = HashUtils.getHash(current);
		writeRecord(storage, relative(storage, current), relative(storage, target), replacementHash, replacement.length, currentHash);
		Files.delete(storage.objectFile(replacementHash));

		// The record survives a crash-loop scenario in the wild; recovery must skip it, not kill every boot.
		assertDoesNotThrow(() -> SelfUpdateSwap.recover(storage.gameDirectory(), storage.dataLocation()));

		assertEquals("old", Files.readString(current));
		assertFalse(Files.exists(target));
		assertTrue(Files.notExists(recordFile(storage)));
		try (var leftovers = Files.list(recordFile(storage).getParent())) {
			assertTrue(leftovers.anyMatch(path -> path.getFileName().toString().startsWith(recordFile(storage).getFileName() + ".corrupt-")));
		}
	}

	@Test
	void anUnreadableRecordIsSetAsideInsteadOfLoopingTheBoot() throws Exception {
		ClientStorage storage = storage();
		Path record = recordFile(storage);
		Files.createDirectories(record.getParent());
		Files.createSymbolicLink(record, record.getParent().resolve("elsewhere"));

		// A record that cannot even be read must not re-log and re-skip every boot either; it joins the evidence.
		assertDoesNotThrow(() -> SelfUpdateSwap.recover(storage.gameDirectory(), storage.dataLocation()));

		assertTrue(Files.notExists(record));
		try (var leftovers = Files.list(record.getParent())) {
			assertTrue(leftovers.anyMatch(path -> path.getFileName().toString().startsWith(record.getFileName() + ".corrupt-")));
		}
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static Path recordFile(ClientStorage storage) {
		return storage.gameDirectory().resolve(StoragePaths.SELF_UPDATE_FILE);
	}

	private static String relative(ClientStorage storage, Path path) {
		return LogicalPath.normalize(storage.gameDirectory().relativize(path).toString());
	}

	private static void writeRecord(ClientStorage storage, String currentPath, String targetPath, String targetSha1, long targetSize, String currentSha1) throws Exception {
		StorageJsons.SelfUpdateFields record = new StorageJsons.SelfUpdateFields();
		record.currentPath = currentPath;
		record.targetPath = targetPath;
		record.targetSha1 = targetSha1;
		record.targetSize = targetSize;
		record.currentSha1 = currentSha1;
		ConfigTools.writeAtomic(recordFile(storage), record);
	}

	private static String store(ClientStorage storage, byte[] bytes) throws Exception {
		Path temporary = Files.createTempFile(storage.objectsDirectory(), ".object-", ".tmp");
		Files.write(temporary, bytes);
		String hash = HashUtils.getHash(temporary);
		Path destination = storage.objectFile(hash);
		Files.createDirectories(destination.getParent());
		if (Files.exists(destination)) {
			assertTrue(FileIntegrity.matches(destination, bytes.length, hash));
			Files.delete(temporary);
		} else Files.move(temporary, destination);
		return hash;
	}
}
