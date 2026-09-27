package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.MissingGenerationContentException;
import pl.skidam.automodpack_core.utils.DurableFiles;

/** Pins the shared recovery boundary for a pointer whose generation content left the data root: one aside artifact, no live pointer, however often it runs. */
class UnresolvableModpackRecoveryTest {

	@Test
	void forgettingAnUnresolvableModpackTwiceLeavesExactlyOneAsideArtifactAndNoStateFile(@TempDir Path temp) throws Exception {
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		Files.createDirectories(storage.stateFile().getParent());
		Files.writeString(storage.stateFile(), "{}", StandardCharsets.UTF_8);
		MissingGenerationContentException cause = new MissingGenerationContentException("test");

		ClientLaunch.forgetUnresolvableModpack(storage, cause);
		ClientLaunch.forgetUnresolvableModpack(storage, cause);

		assertFalse(Files.exists(storage.stateFile()));
		try (Stream<Path> files = Files.list(storage.stateFile().getParent())) {
			assertEquals(1, files.filter(path -> path.getFileName().toString().contains(DurableFiles.CORRUPT_ASIDE_MARKER)).count());
		}
	}
}
