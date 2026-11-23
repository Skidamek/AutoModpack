package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTransactionCorruptionTest {
	private static final String TOKEN = "a".repeat(40);

	@TempDir
	Path tempDir;

	@Test
	void corruptTransactionIsSetAsideAndStartupSeesNoTransaction() throws Exception {
		Path file = tempDir.resolve("update-transaction.json");
		// A transaction written by an older build whose RestartReason constants no longer exist.
		Files.writeString(file, "{\"schemaVersion\":" + UpdateTransaction.CURRENT_SCHEMA_VERSION + ",\"plan\":{\"restartReasons\":[\"GONE_REASON\"]}}");

		assertNull(new UpdateJournal(file).read());
		assertTrue(Files.notExists(file));
		try (var leftovers = Files.list(tempDir)) {
			List<Path> aside = leftovers.filter(path -> path.getFileName().toString().startsWith("update-transaction.json.corrupt-")).toList();
			assertEquals(1, aside.size());
			String evidence = Files.readString(aside.get(0));
			assertTrue(evidence.contains("GONE_REASON"), evidence);
		}
		assertNull(new UpdateJournal(file).read());
	}

	@Test
	void emptyPlanObjectIsSetAsideAndStartupSeesNoTransaction() throws Exception {
		Path file = tempDir.resolve("update-transaction.json");
		Files.writeString(file, "{\"schemaVersion\":" + UpdateTransaction.CURRENT_SCHEMA_VERSION + ",\"purpose\":\"MODPACK_UPDATE\",\"phase\":\"PLANNED\",\"plan\":{}}");

		assertNull(new UpdateJournal(file).read());
		assertTrue(Files.notExists(file));
		try (var leftovers = Files.list(tempDir)) {
			assertEquals(1, leftovers.filter(path -> path.getFileName().toString().startsWith("update-transaction.json.corrupt-")).toList().size());
		}
	}

	@Test
	void incompleteTransactionIsSetAsideAndStartupSeesNoTransaction() throws Exception {
		Path file = tempDir.resolve("update-transaction.json");
		// A transaction whose planned sections never reached the disk cannot drive any recovery; only a whole one loads.
		Files.writeString(file, "{\"schemaVersion\":" + UpdateTransaction.CURRENT_SCHEMA_VERSION + ",\"purpose\":\"MODPACK_UPDATE\",\"phase\":\"PLANNED\"}");

		assertNull(new UpdateJournal(file).read());
		assertTrue(Files.notExists(file));
		try (var leftovers = Files.list(tempDir)) {
			List<Path> aside = leftovers.filter(path -> path.getFileName().toString().startsWith("update-transaction.json.corrupt-")).toList();
			assertEquals(1, aside.size());
		}
	}

	@Test
	void aTransactionFromAnotherSchemaGenerationIsSetAsideAndStartupSeesNoTransaction() throws Exception {
		Path file = tempDir.resolve("update-transaction.json");
		// A transaction a newer build wrote: this build must not drive its recovery, in either schema direction.
		Files.writeString(file, "{\"schemaVersion\":" + (UpdateTransaction.CURRENT_SCHEMA_VERSION + 1) + ",\"transactionId\":\"t1\",\"purpose\":\"MODPACK_UPDATE\",\"phase\":\"PLANNED\",\"targetPlatform\":\"linux\","
				+ "\"plan\":{\"modpackId\":\"packaa1\",\"contentToken\":\"" + TOKEN + "\",\"policySha1\":\"" + TOKEN + "\",\"ledgerDigest\":\"" + TOKEN + "\","
				+ "\"operations\":[],\"projectedFinalState\":[],\"restartReasons\":[\"SELECTED_MODPACK\"],\"preservations\":[],\"baselineCaptures\":[],\"conflicts\":[],\"generatedCopies\":[],"
				+ "\"plannedClientConfig\":null,\"consequences\":{\"changes\":[],\"effects\":[]}}}");

		assertNull(new UpdateJournal(file).read());
		assertTrue(Files.notExists(file));
		try (var leftovers = Files.list(tempDir)) {
			assertEquals(1, leftovers.filter(path -> path.getFileName().toString().startsWith("update-transaction.json.corrupt-")).toList().size());
		}
	}

	@Test
	void validTransactionStillLoads() throws Exception {
		Path file = tempDir.resolve("update-transaction.json");
		Files.writeString(file, "{\"schemaVersion\":" + UpdateTransaction.CURRENT_SCHEMA_VERSION + ",\"transactionId\":\"t1\",\"purpose\":\"MODPACK_UPDATE\",\"phase\":\"PLANNED\",\"targetPlatform\":\"linux\","
				+ "\"plan\":{\"modpackId\":\"packaa1\",\"contentToken\":\"" + TOKEN + "\",\"policySha1\":\"" + TOKEN + "\",\"ledgerDigest\":\"" + TOKEN + "\","
				+ "\"operations\":[],\"projectedFinalState\":[],\"restartReasons\":[\"SELECTED_MODPACK\"],\"preservations\":[],\"baselineCaptures\":[],\"conflicts\":[],\"generatedCopies\":[],"
				+ "\"plannedClientConfig\":null,\"consequences\":{\"changes\":[],\"effects\":[]}}}");

		UpdateJournal.Cursor cursor = new UpdateJournal(file).read();

		assertNotNull(cursor);
		assertEquals(UpdateTransaction.Purpose.MODPACK_UPDATE, cursor.transaction().purpose);
		assertEquals(Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), cursor.transaction().plan().restartReasons());
		assertTrue(Files.exists(file));
	}
}
