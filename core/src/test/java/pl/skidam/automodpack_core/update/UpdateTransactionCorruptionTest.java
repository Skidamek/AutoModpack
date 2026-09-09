package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTransactionCorruptionTest {
	@TempDir
	Path tempDir;

	@Test
	void corruptTransactionIsSetAsideAndStartupSeesNoTransaction() throws Exception {
		Path file = tempDir.resolve("update-transaction.json");
		// A transaction written by an older build whose RestartReason constants no longer exist.
		Files.writeString(file, "{\"schemaVersion\":1,\"purpose\":\"MODPACK_UPDATE\",\"phase\":\"PLANNED\",\"restartReasons\":[\"GONE_REASON\"]}");

		assertNull(UpdateTransaction.read(file));
		assertTrue(Files.notExists(file));
		try (var leftovers = Files.list(tempDir)) {
			List<Path> aside = leftovers.filter(path -> path.getFileName().toString().startsWith("update-transaction.json.corrupt-")).toList();
			assertEquals(1, aside.size());
			String evidence = Files.readString(aside.get(0));
			assertTrue(evidence.contains("GONE_REASON"), evidence);
		}
		assertNull(UpdateTransaction.read(file));
	}

	@Test
	void validTransactionStillLoads() throws Exception {
		Path file = tempDir.resolve("update-transaction.json");
		Files.writeString(file, "{\"schemaVersion\":1,\"transactionId\":\"t1\",\"purpose\":\"MODPACK_UPDATE\",\"phase\":\"PLANNED\",\"targetPlatform\":\"linux\","
				+ "\"restartReasons\":[\"SELECTED_MODPACK\"]}");

		UpdateTransaction transaction = UpdateTransaction.read(file);

		assertNotNull(transaction);
		assertEquals(UpdateTransaction.Purpose.MODPACK_UPDATE, transaction.purpose);
		assertEquals(List.of(UpdatePlan.RestartReason.SELECTED_MODPACK), transaction.restartReasons);
		assertTrue(Files.exists(file));
	}
}
