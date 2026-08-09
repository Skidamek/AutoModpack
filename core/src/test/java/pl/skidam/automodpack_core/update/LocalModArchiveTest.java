package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

class LocalModArchiveTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void archivesSelectedFilesAndVerifiesTheManifestPayload() throws Exception {
		ClientStorage storage = storage();
		Path selected = writeMod(storage, "selected.jar", "selected");
		Path untouched = writeMod(storage, "untouched.jar", "untouched");
		LocalModArchive.Snapshot candidates;
		try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			candidates = LocalModArchive.candidates(storage, null, cache);
			LocalModArchive.archive(storage, candidates.entries().stream().filter(entry -> entry.originalPath().endsWith("selected.jar")).toList(), cache);
		}
		assertFalse(Files.exists(selected));
		assertTrue(Files.exists(untouched));
		LocalModArchive.Snapshot archived = LocalModArchive.snapshot(storage);
		assertEquals(1, archived.entries().size());
		Files.writeString(storage.localModArchivePayload(archived.entries().get(0).entryId()), "tampered", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> LocalModArchive.snapshot(storage));
	}

	@Test
	void refusesDifferentRestoreDestinationButAllowsIdenticalBytes() throws Exception {
		ClientStorage storage = storage();
		Path source = writeMod(storage, "restore.jar", "original");
		LocalModArchive.Snapshot candidate;
		try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			candidate = LocalModArchive.candidates(storage, null, cache);
			LocalModArchive.archive(storage, candidate.entries(), cache);
		}
		Files.writeString(source, "different", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> LocalModArchive.restore(storage, candidate.entries().get(0).entryId()));
		assertEquals(1, LocalModArchive.snapshot(storage).entries().size());
		Files.writeString(source, "original", StandardCharsets.UTF_8);
		LocalModArchive.restore(storage, candidate.entries().get(0).entryId());
		assertTrue(Files.exists(source));
		assertTrue(LocalModArchive.snapshot(storage).entries().isEmpty());
	}

	@Test
	void rejectsManifestMetadataWithAnUnrelatedEntryId() throws Exception {
		ClientStorage storage = storage();
		writeMod(storage, "original.jar", "original");
		try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			LocalModArchive.archive(storage, LocalModArchive.candidates(storage, null, cache).entries(), cache);
		}
		ClientStorageJsons.ClientLocalModArchiveFields manifest = ConfigTools.read(storage.localModArchiveManifest(), ClientStorageJsons.ClientLocalModArchiveFields.class).orElseThrow();
		manifest.entries.get(0).originalPath = "mods/redirected.jar";
		ConfigTools.writeAtomic(storage.localModArchiveManifest(), manifest);
		assertThrows(IOException.class, () -> LocalModArchive.snapshot(storage));
	}

	@Test
	void archiveAndRestoreLeaveOnlyTheCommittedFile() throws Exception {
		ClientStorage storage = storage();
		Path source = writeMod(storage, "transaction.jar", "transaction");
		LocalModArchive.ArchiveEntry candidate;
		try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			candidate = LocalModArchive.candidates(storage, null, cache).entries().get(0);
			LocalModArchive.archive(storage, List.of(candidate), cache);
		}
		assertFalse(Files.exists(source));
		assertTrue(SmartFileUtils.isValidFile(storage.localModArchivePayload(candidate.entryId()), candidate.size(), candidate.sha1()));
		LocalModArchive.restore(storage, candidate.entryId());
		assertTrue(SmartFileUtils.isValidFile(source, candidate.size(), candidate.sha1()));
		assertFalse(Files.exists(storage.localModArchivePayload(candidate.entryId())));
		assertTrue(LocalModArchive.snapshot(storage).entries().isEmpty());
	}

	@Test
	void doesNotTraverseSymlinkedModOrArchivePayload() throws Exception {
		ClientStorage storage = storage();
		Path outside = Files.writeString(temporaryDirectory.resolve("outside.jar"), "outside", StandardCharsets.UTF_8);
		Path link = storage.modsDirectory().resolve("link.jar");
		try {
			Files.createSymbolicLink(link, outside);
		} catch (UnsupportedOperationException | FileSystemException unsupported) {
			return;
		}
		try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			assertTrue(LocalModArchive.candidates(storage, null, cache).entries().isEmpty());
		}
		Path real = writeMod(storage, "real.jar", "real");
		LocalModArchive.Snapshot candidate;
		try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			candidate = LocalModArchive.candidates(storage, null, cache);
			LocalModArchive.archive(storage, candidate.entries().stream().filter(entry -> entry.originalPath().endsWith("real.jar")).toList(), cache);
		}
		LocalModArchive.ArchiveEntry archived = LocalModArchive.snapshot(storage).entries().get(0);
		Path payload = storage.localModArchivePayload(archived.entryId());
		Files.delete(payload);
		Files.createSymbolicLink(payload, outside);
		assertThrows(IOException.class, () -> LocalModArchive.snapshot(storage));
		assertFalse(Files.exists(real));
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = ClientStorage.fromGameDirectory(temporaryDirectory.resolve("game"));
		storage.ensureRoots();
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static Path writeMod(ClientStorage storage, String name, String content) throws Exception {
		return Files.writeString(storage.modsDirectory().resolve(name), content, StandardCharsets.UTF_8);
	}
}
