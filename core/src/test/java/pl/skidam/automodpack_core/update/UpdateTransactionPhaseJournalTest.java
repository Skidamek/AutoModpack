package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.client.UpdateRecovery;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

/**
 * The journal is one JSON-lines file with one owner: line 1 is the plan document, every later line is a phase record.
 * No sidecar exists and the document carries no phase. These tests kill the apply at every persisted boundary and
 * corrupt the tail in every way a power cut can, because recovery's only job is to read that one file correctly.
 */
class UpdateTransactionPhaseJournalTest {
	private static final String MOD = "mods/phase.jar";
	private static final byte[] BYTES = "phase record payload".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path temporaryDirectory;

	private record Installed(ClientStorage storage, SelectedModpackTarget target, UpdatePlan plan) {}

	private static UpdateCommit.Built built(Installed installed) throws IOException {
		return new UpdateCommit.Built(UpdateTestFixtures.createTransaction(installed.storage(), installed.plan(), installed.target()), installed.target());
	}

	private Installed install() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		String hash = UpdateTestFixtures.store(storage, BYTES);
		SelectedModpackTarget target = UpdateTestFixtures.target(storage, MOD, "mod", false, hash, BYTES.length);
		UpdatePlan plan = UpdateTestFixtures.plan(target, List.of(new Operation(Root.PROJECTION, MOD, OperationType.INSTALL_OBJECT, hash, BYTES.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, MOD, true, hash, BYTES.length)));
		return new Installed(storage, target, plan);
	}

	private static UpdateCommit.Probe crashAt(UpdateCommit.Step step) {
		return new UpdateCommit.Probe() {
			@Override
			public void reached(UpdateCommit.Step reached) {
				if (reached == step) throw new Crash();
			}
		};
	}

	private static final class Crash extends Error {}

	@Test
	void aWholeApplyWritesThePlanOnceAppendsItsPhasesAndRetiresTheJournal() throws Exception {
		Installed installed = install();
		UpdateCommit.Outcome outcome = UpdateTestFixtures.commits(installed.storage())
				.run(new UpdateCommit.Fresh(() -> built(installed), null));

		assertInstanceOf(UpdateCommit.Applied.class, outcome);
		assertFalse(Files.exists(installed.storage().transactionFile()));
		assertArrayEquals(BYTES, Files.readAllBytes(installed.storage().activePath(MOD)));
	}

	/**
	 * Every persisted boundary, killed after the record is forced and before the next step. The journal's phase comes from
	 * the records alone; the document is never rewritten at a boundary and names no phase.
	 */
	@ParameterizedTest
	@EnumSource(UpdateCommit.Step.class)
	void everyPhaseBoundaryReplaysToTheSameState(UpdateCommit.Step step) throws Exception {
		Installed installed = install();
		UpdateCommit crashing = new UpdateCommit(installed.storage(), crashAt(step));

		boolean crashed = false;
		try {
			crashing.run(new UpdateCommit.Fresh(() -> built(installed), null));
		} catch (Crash expected) {
			crashed = true;
		}
		assertTrue(crashed, "the probe must stop this apply at " + step);

		UpdateTransaction.Phase expected = switch (step) {
			case JOURNALED -> UpdateTransaction.Phase.PLANNED;
			case LIVE_APPLIED -> UpdateTransaction.Phase.PREPARING;
			case PROJECTION_MARKED, PROJECTION_CONVERGED, FINALIZED -> UpdateTransaction.Phase.PROJECTED;
			case COMMITTED -> UpdateTransaction.Phase.COMMITTED;
		};
		Path journal = installed.storage().transactionFile();
		assertEquals(expected, new UpdateJournal(installed.storage()).read().phase(), "read phase after a " + step + " crash");
		assertFalse(document(journal).has("phase"), "the plan document itself names no phase and is never rewritten at a boundary");
		assertEquals(switch (step) {
			case JOURNALED -> 0;
			case LIVE_APPLIED -> 1;
			case PROJECTION_MARKED, PROJECTION_CONVERGED, FINALIZED -> 2;
			case COMMITTED -> 3;
		}, recordCount(journal), "one record per persisted boundary after the plan");

		UpdateCommit.Outcome recovered = UpdateTestFixtures.commits(installed.storage()).run(new UpdateCommit.Recover(null));

		assertInstanceOf(UpdateCommit.Applied.class, recovered, "replay after a " + step + " crash: " + recovered);
		assertArrayEquals(BYTES, Files.readAllBytes(installed.storage().activePath(MOD)));
		assertFalse(Files.exists(journal));
	}

	@Test
	void aCommittedJournalIsRetiredByTheNextFreshApplyWithItsRecords() throws Exception {
		Installed installed = install();
		UpdateCommit crashing = new UpdateCommit(installed.storage(), crashAt(UpdateCommit.Step.COMMITTED));
		try {
			crashing.run(new UpdateCommit.Fresh(() -> built(installed), null));
		} catch (Crash expected) {
			// the tail has not run
		}
		assertEquals(UpdateTransaction.Phase.COMMITTED, new UpdateJournal(installed.storage()).read().phase());

		assertInstanceOf(UpdateCommit.Applied.class, UpdateTestFixtures.commits(installed.storage())
				.run(new UpdateCommit.Fresh(() -> built(installed), null)));
		assertFalse(Files.exists(installed.storage().transactionFile()));
	}

	@Test
	void quarantiningAStartedJournalCarriesItsRecordsWithIt() throws Exception {
		Installed installed = install();
		UpdateCommit crashing = new UpdateCommit(installed.storage(), crashAt(UpdateCommit.Step.LIVE_APPLIED));
		try {
			crashing.run(new UpdateCommit.Fresh(() -> built(installed), null));
		} catch (Crash expected) {
			// a started journal, quarantined by the next review
		}

		Path quarantined = PublicationSweep.retireJournal(installed.storage());

		assertNotNull(quarantined);
		assertFalse(Files.exists(installed.storage().transactionFile()));
		assertEquals(UpdateTransaction.Phase.PREPARING, new UpdateJournal(quarantined).read().phase(), "the evidence keeps its phase records");
	}

	/**
	 * The invariant recovery's replan arm is justified by: a journal that never wrote anything may be retired, one that
	 * wrote may not. A PREPARING record is that proof, so a replan-required outcome over it must refuse the retirement.
	 */
	@Test
	void aReplanRequiredOutcomeAfterAPreparingRecordCannotRetireTheJournal() throws Exception {
		Installed installed = install();
		UpdateCommit crashing = new UpdateCommit(installed.storage(), crashAt(UpdateCommit.Step.LIVE_APPLIED));
		try {
			crashing.run(new UpdateCommit.Fresh(() -> built(installed), null));
		} catch (Crash expected) {
			// the started journal is what this test recovers
		}
		UpdateTransaction pending = new UpdateJournal(installed.storage()).read().transaction();
		UpdateCommit.ReplanRequired replan = new UpdateCommit.ReplanRequired(pending, null, null, "an input moved");

		assertThrows(IllegalStateException.class, () -> UpdateRecovery.blockedRecovery(installed.storage(), pending, replan, () -> replan));
		assertTrue(Files.exists(installed.storage().transactionFile()), "a started journal is only rolled forward, never retired");
		assertEquals(UpdateTransaction.Phase.PREPARING, new UpdateJournal(installed.storage()).read().phase(), "the journal keeps the record that refused its retirement");
	}

	// ---- Tail corruption: only a torn final line is a crash to ignore ----

	@Test
	void aTornPartialRecordIsIgnored() throws Exception {
		Fixture fixture = Fixture.crashedAt(install(), UpdateCommit.Step.LIVE_APPLIED);
		fixture.appendRaw("{\"schemaVersion\":1,\"transac");
		assertEquals(UpdateTransaction.Phase.PREPARING, fixture.cursor().phase());
		assertStillRecovers(fixture);
	}

	@Test
	void aTornRecordWithAPartialUtf8SequenceIsIgnored() throws Exception {
		Fixture fixture = Fixture.crashedAt(install(), UpdateCommit.Step.LIVE_APPLIED);
		byte[] fragment = ("\n{\"schemaVersion\":1,\"transactionId\":\"" + fixture.transactionId() + "\",\"phase\":\"PROJECTED\",\"pad\":\"").getBytes(StandardCharsets.UTF_8);
		byte[] truncatedEuro = {(byte) 0xE2, (byte) 0x82};
		fixture.appendBytes(fragment, truncatedEuro);
		assertEquals(UpdateTransaction.Phase.PREPARING, fixture.cursor().phase(), "a tail torn inside UTF-8 must not sink the document");
		assertStillRecovers(fixture);
	}

	@Test
	void aCompleteRecordWithoutItsTrailingNewlineIsReadAndItsNewlineRestored() throws Exception {
		Fixture fixture = Fixture.crashedAt(install(), UpdateCommit.Step.LIVE_APPLIED);
		fixture.appendRaw("\n{\"schemaVersion\":1,\"transactionId\":\"" + fixture.transactionId() + "\",\"phase\":\"PROJECTED\"}");
		assertEquals(UpdateTransaction.Phase.PROJECTED, fixture.cursor().phase());
		assertTrue(Files.readString(fixture.journal(), StandardCharsets.UTF_8).endsWith("\n"), "every line ends with a newline, so the next append cannot fuse two records");
		assertStillRecovers(fixture);
	}

	/** Only the final unterminated line is a tear; a terminated line of garbage is corruption like any other. */
	@Test
	void aTerminatedGarbageLineSetsTheJournalAside() throws Exception {
		Fixture fixture = Fixture.crashedAt(install(), UpdateCommit.Step.PROJECTION_MARKED);
		fixture.appendRaw("\n{\"schemaVersion\":1\n");

		assertNull(fixture.cursor());
		assertSetAside(fixture.journal());
	}

	/**
	 * A phase name this build does not know is unusable content, never a record to skip: skipping it would read the phase
	 * from an earlier record and walk the half-applied transaction backwards.
	 */
	@Test
	void anUnknownPhaseNameSetsTheJournalAsideInsteadOfReadingAnEarlierPhase() throws Exception {
		Fixture fixture = Fixture.crashedAt(install(), UpdateCommit.Step.PROJECTION_MARKED);
		fixture.appendRaw("\n{\"schemaVersion\":1,\"transactionId\":\"" + fixture.transactionId() + "\",\"phase\":\"EXPLODED\"}");

		assertNull(fixture.cursor());
		assertSetAside(fixture.journal());
	}

	@Test
	void aRecordOfAnotherTransactionSetsTheJournalAside() throws Exception {
		Fixture fixture = Fixture.crashedAt(install(), UpdateCommit.Step.PROJECTION_MARKED);
		fixture.appendRaw("\n{\"schemaVersion\":1,\"transactionId\":\"00000000-0000-0000-0000-000000000000\",\"phase\":\"COMMITTED\"}\n");

		assertNull(fixture.cursor());
		assertSetAside(fixture.journal());
	}

	@Test
	void aRecordWithAnUnknownRecordSchemaSetsTheJournalAside() throws Exception {
		Fixture fixture = Fixture.crashedAt(install(), UpdateCommit.Step.PROJECTION_MARKED);
		fixture.appendRaw("\n{\"schemaVersion\":9,\"transactionId\":\"" + fixture.transactionId() + "\",\"phase\":\"COMMITTED\"}\n");

		assertNull(fixture.cursor());
		assertSetAside(fixture.journal());
	}

	@Test
	void aDocumentWithoutRecordsReadsAsThePlannedPhase() throws Exception {
		Installed installed = install();
		UpdateTransaction transaction = UpdateTestFixtures.createTransaction(installed.storage(), installed.plan(), installed.target());
		new UpdateJournal(installed.storage()).write(transaction);

		UpdateJournal.Cursor cursor = new UpdateJournal(installed.storage()).read();
		assertNotNull(cursor);
		assertEquals(UpdateTransaction.Phase.PLANNED, cursor.phase());
		assertFalse(cursor.started());
		assertFalse(cursor.published());
	}

	@Test
	void aSchemaOneJournalIsSetAsideAndTheNextApplyReplansInsteadOfMisreadingIt() throws Exception {
		Installed installed = install();
		UpdateTransaction transaction = UpdateTestFixtures.createTransaction(installed.storage(), installed.plan(), installed.target());
		transaction.schemaVersion = 1;
		new UpdateJournal(installed.storage()).write(transaction);

		assertNull(new UpdateJournal(installed.storage()).read(), "an older document carries no valid phase records");
		assertSetAside(installed.storage().transactionFile());

		UpdateCommit.Outcome replanned = UpdateTestFixtures.commit(installed.storage(), installed.plan(), installed.target());
		assertInstanceOf(UpdateCommit.Applied.class, replanned);
		assertArrayEquals(BYTES, Files.readAllBytes(installed.storage().activePath(MOD)));
	}

	@Test
	void anObserverReadsTheSamePhasesButNeverSetsACorruptJournalAside() throws Exception {
		Fixture fixture = Fixture.crashedAt(install(), UpdateCommit.Step.COMMITTED);
		assertEquals(UpdateTransaction.Phase.COMMITTED, new UpdateJournal(fixture.storage()).readWithoutRepair().phase());

		Files.writeString(fixture.journal(), "{not json", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> new UpdateJournal(fixture.storage()).readWithoutRepair());
		assertTrue(Files.exists(fixture.journal()), "the observer leaves the evidence where recovery will find it");
	}

	// ---- Helpers ----

	private record Fixture(ClientStorage storage, String transactionId) {
		static Fixture crashedAt(Installed installed, UpdateCommit.Step step) throws Exception {
			UpdateCommit crashing = new UpdateCommit(installed.storage(), crashAt(step));
			try {
				crashing.run(new UpdateCommit.Fresh(() -> built(installed), null));
			} catch (Crash expected) {
				// the boundary is now durable
			}
			return new Fixture(installed.storage(), new UpdateJournal(installed.storage()).read().transaction().transactionId);
		}

		Path journal() {
			return storage.transactionFile();
		}

		UpdateJournal.Cursor cursor() throws IOException {
			return new UpdateJournal(storage).read();
		}

		void appendRaw(String text) throws Exception {
			Files.write(journal(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
		}

		void appendBytes(byte[]... chunks) throws Exception {
			try (var channel = Files.newOutputStream(journal(), StandardOpenOption.APPEND)) {
				for (byte[] chunk : chunks) channel.write(chunk);
			}
		}
	}

	private void assertStillRecovers(Fixture fixture) throws Exception {
		assertInstanceOf(UpdateCommit.Applied.class, UpdateTestFixtures.commits(fixture.storage()).run(new UpdateCommit.Recover(null)));
		assertFalse(Files.exists(fixture.journal()));
	}

	private static void assertSetAside(Path journal) throws Exception {
		assertFalse(Files.exists(journal), "unusable content is never left where the next boot would read it as a plan");
		try (var siblings = Files.list(journal.getParent())) {
			assertTrue(siblings.anyMatch(path -> path.getFileName().toString().startsWith(journal.getFileName() + ".corrupt-")), "the evidence stays beside where the journal lived");
		}
	}

	/** The compact document is the file's first line. */
	private static JsonObject document(Path journal) throws Exception {
		return JsonParser.parseString(Files.readString(journal, StandardCharsets.UTF_8).split("\n", 2)[0]).getAsJsonObject();
	}

	private static int recordCount(Path journal) throws Exception {
		String text = Files.readString(journal, StandardCharsets.UTF_8);
		String[] lines = text.split("\n", -1);
		int count = 0;
		for (int index = 1; index < lines.length - 1; index++) if (!lines[index].isBlank()) count++;
		return count;
	}
}
