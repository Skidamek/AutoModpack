package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
		assertThrows(java.io.IOException.class, () -> LocalModArchive.snapshot(storage));
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
		assertThrows(java.io.IOException.class, () -> LocalModArchive.restore(storage, candidate.entries().get(0).entryId()));
		assertEquals(1, LocalModArchive.snapshot(storage).entries().size());
		Files.writeString(source, "original", StandardCharsets.UTF_8);
		LocalModArchive.restore(storage, candidate.entries().get(0).entryId());
		assertTrue(Files.exists(source));
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
		assertThrows(java.io.IOException.class, () -> LocalModArchive.snapshot(storage));
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
