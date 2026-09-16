package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.utils.HashUtils;

class RecoveryArchiveTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void archivesCasObjectOnceAndRecordsLogicalPath() throws Exception {
		Path store = temporaryDirectory.resolve("store");
		Path recovery = temporaryDirectory.resolve("recovery");
		Files.createDirectories(store);
		Path source = Files.writeString(store.resolve("source.tmp"), "deleted bytes", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		Path object = Files.move(source, store.resolve(hash));

		Path archived = RecoveryArchive.archive(store, recovery, "config/old.json", hash, Files.size(object));
		Path repeated = RecoveryArchive.archive(store, recovery, "config/old.json", hash, Files.size(object));

		assertEquals(archived, repeated);
		assertArrayEquals(Files.readAllBytes(object), Files.readAllBytes(archived));
		ClientStorageJsons.ClientRecoveryArchiveFields archive = RecoveryArchive.read(recovery);
		assertEquals(1, archive.entries.size());
		assertEquals("config/old.json", archive.entries.get(0).logicalPath);
		assertEquals(hash, archive.entries.get(0).sha1);
		Files.delete(object);
		assertEquals(1, RecoveryArchive.read(recovery).entries.size());
		assertEquals(archived, RecoveryArchive.archive(store, recovery, "config/old.json", hash, Files.size(archived)));
	}

	@Test
	void recordsRecoveryProvenance() throws Exception {
		Path store = temporaryDirectory.resolve("store");
		Path recovery = temporaryDirectory.resolve("recovery");
		Files.createDirectories(store);
		Path source = Files.writeString(store.resolve("source.tmp"), "deleted bytes", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		Path object = Files.move(source, store.resolve(hash));
		String generationId = "a".repeat(40);
		String preservedAt = "2026-08-02T12:34:56Z";

		RecoveryArchive.archive(store, recovery, "config/old.json", hash, Files.size(object), generationId, preservedAt);

		ClientStorageJsons.ClientRecoveryArchiveFields.EntryFields entry = RecoveryArchive.read(recovery).entries.get(0);
		assertEquals(generationId, entry.sourceGenerationId);
		assertEquals(preservedAt, entry.preservedAt);
	}

	@Test
	void keepsArchivesInSeparateRecoveryRoots() throws Exception {
		Path store = temporaryDirectory.resolve("store");
		Path recoveryRoot = temporaryDirectory.resolve("recovery");
		Files.createDirectories(store);
		Path source = Files.writeString(store.resolve("source.tmp"), "deleted bytes", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		Path object = Files.move(source, store.resolve(hash));
		long size = Files.size(object);

		Path firstArchived = RecoveryArchive.archive(store, recoveryRoot.resolve("first"), "config/one.json", hash, size);
		Path secondArchived = RecoveryArchive.archive(store, recoveryRoot.resolve("second"), "config/two.json", hash, size);

		assertNotEquals(firstArchived, secondArchived);
		assertEquals(1, RecoveryArchive.read(recoveryRoot.resolve("first")).entries.size());
		assertEquals(1, RecoveryArchive.read(recoveryRoot.resolve("second")).entries.size());
		assertEquals("config/one.json", RecoveryArchive.read(recoveryRoot.resolve("first")).entries.get(0).logicalPath);
		assertEquals("config/two.json", RecoveryArchive.read(recoveryRoot.resolve("second")).entries.get(0).logicalPath);
		assertArrayEquals(Files.readAllBytes(firstArchived), Files.readAllBytes(secondArchived));
	}

	@Test
	void rejectsUnsafePathsAndCorruptCasObjects() throws Exception {
		Path store = temporaryDirectory.resolve("store");
		Files.createDirectories(store);
		Path source = Files.writeString(store.resolve("source.tmp"), "deleted bytes", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		Files.move(source, store.resolve(hash));

		assertThrows(IOException.class, () -> RecoveryArchive.archive(store, temporaryDirectory.resolve("recovery"), "../outside", hash, 13));
		assertThrows(IOException.class, () -> RecoveryArchive.archive(store, temporaryDirectory.resolve("recovery"), "config/old.json", hash, 99));
	}
}
