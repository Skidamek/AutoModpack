package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.HashUtils;

/** The projection state document: P1-P5 of {@link ProjectionState} at the document level. */
class ProjectionStateTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void anInstanceWithoutADocumentIsSettledAtRevisionZero() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		ProjectionState state = ProjectionState.read(storage);
		assertFalse(state.converging());
		assertEquals(0, state.revision());
		assertTrue(state.fallback().isEmpty());
	}

	@Test
	void reenteringAConvergenceKeepsTheFirstFallbackAndEveryWriteRaisesTheRevision() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		ProjectionState.Row first = new ProjectionState.Row("mods/a.jar", HashUtils.sha1("a".getBytes(StandardCharsets.UTF_8)), 1);
		ProjectionState.Row second = new ProjectionState.Row("mods/b.jar", HashUtils.sha1("b".getBytes(StandardCharsets.UTF_8)), 1);

		ProjectionState.converging(storage, "one", List.of(first));
		ProjectionState again = ProjectionState.converging(storage, "two", List.of(second));

		assertEquals(List.of(first), ProjectionState.read(storage).fallback(), "the first fallback is the last complete tree");
		assertEquals(2, again.revision());
		assertEquals("two", ProjectionState.read(storage).transactionId());
		ProjectionState settled = ProjectionState.settled(storage, "two");
		assertEquals(3, settled.revision());
		assertTrue(ProjectionState.read(storage).fallback().isEmpty());
	}

	@Test
	void settlingTwiceForOneTransactionDoesNotMoveTheRevision() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		ProjectionState.settled(storage, "tx");
		ProjectionState replayed = ProjectionState.settled(storage, "tx");
		assertEquals(1, replayed.revision());
		assertEquals(1, ProjectionState.read(storage).revision());
	}

	@Test
	void aSettledDocumentCarryingAFallbackIsInvalid() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Files.createDirectories(storage.projectionStateFile().getParent());
		Files.writeString(storage.projectionStateFile(), "{\"version\":1,\"phase\":\"SETTLED\",\"revision\":1,\"fallback\":[{\"path\":\"a\",\"sha1\":\"" + "0".repeat(40) + "\",\"size\":1}]}");
		assertThrows(IOException.class, () -> ProjectionState.read(storage));
	}

	@Test
	void readingNeverSetsTheDocumentAside() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Files.createDirectories(storage.projectionStateFile().getParent());
		Files.writeString(storage.projectionStateFile(), "garbage");
		assertThrows(IOException.class, () -> ProjectionState.read(storage));
		assertTrue(Files.exists(storage.projectionStateFile()));
	}
}
