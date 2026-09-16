package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.HashUtils;

class GeneratedCopyStateCorruptionTest {
	private static final String MODPACK_ID = "abc1234";

	@TempDir
	Path tempDir;

	@Test
	void stateWrittenInAForgottenFormatIsSetAsideAndReadsEmpty() throws Exception {
		ClientStorage storage = TestDataRoot.open(tempDir.resolve("game"), tempDir.resolve("data"));
		String contentToken = HashUtils.sha1("generation");
		String selectionDigest = HashUtils.sha1("selection");
		Path path = storage.generatedCopiesFile(MODPACK_ID, contentToken, selectionDigest);
		// A state written by a build whose format carried generationId, which today's format reads as a missing content token.
		Files.createDirectories(path.getParent());
		Files.writeString(path, "{\"schemaVersion\":1,\"generationId\":\"" + contentToken + "\",\"selectionDigest\":\"" + selectionDigest + "\",\"entries\":[]}",
				StandardCharsets.UTF_8);

		GeneratedCopyState state = GeneratedCopyState.read(storage, MODPACK_ID, contentToken, selectionDigest);

		assertTrue(state.entries().isEmpty());
		assertTrue(Files.notExists(path));
		List<Path> aside;
		try (var leftovers = Files.list(path.getParent())) {
			aside = leftovers.filter(entry -> entry.getFileName().toString().startsWith(path.getFileName().toString() + ".corrupt-")).toList();
		}
		assertEquals(1, aside.size());
		assertTrue(Files.readString(aside.get(0)).contains("generationId"));
	}

	@Test
	void asideLeftoversNeverBlockTheOwnershipSweep() throws Exception {
		ClientStorage storage = TestDataRoot.open(tempDir.resolve("game"), tempDir.resolve("data"));
		Path generationDirectory = storage.generatedCopiesGenerationDirectory(MODPACK_ID, HashUtils.sha1("generation"));
		Files.createDirectories(generationDirectory);
		Files.writeString(generationDirectory.resolve(HashUtils.sha1("selection") + ".json.corrupt-1757000000000"), "{}", StandardCharsets.UTF_8);
		Files.writeString(generationDirectory.resolve(".state.json.leftover.tmp"), "{}", StandardCharsets.UTF_8);

		ClientObjectStore.publishOwnership(storage);
	}
}
