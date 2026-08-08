package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
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

		Jsons.ClientQuarantineFields fields = ConfigTools.read(storage.quarantineManifest("abc1234"), Jsons.ClientQuarantineFields.class).orElseThrow();
		fields.entries.get(0).sourceHash = "0".repeat(40);
		ConfigTools.writeAtomic(storage.quarantineManifest("abc1234"), fields);

		assertThrows(IOException.class, () -> QuarantineArchive.read(storage, "abc1234"));
	}
}
