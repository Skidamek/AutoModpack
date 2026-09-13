package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

/**
 * The transaction is the write-ahead journal. Minecraft 1.18 ships Gson 2.8.9, which cannot deserialize records, so
 * every durable type on this graph is a class. These tests keep it that way and prove a real transaction round-trips.
 */
class UpdateTransactionFieldsTest {
	private static final String OBJECT_HASH = "1111111111111111111111111111111111111111";

	@TempDir
	Path temporaryDirectory;

	@Test
	void aRealTransactionRoundTripsThroughTheDurableDocument() throws Exception {
		OwnershipLedger ledger = OwnershipLedger.empty("packaa1");
		ChangeSet consequences = ChangeSet.of(new ChangeSet.Change("mods/a.jar", ChangeSet.Kind.ADDED,
				List.of(new ChangeSet.Occurrence("PROJECTION", "mods/a.jar", 1, null, null, OBJECT_HASH, "mod", List.of(), List.of()))));
		UpdatePlan plan = new UpdatePlan("packaa1", new PackTarget("packaa1", "a".repeat(40), "b".repeat(40), ledger.digest()),
				List.of(new Operation(Root.PROJECTION, "mods/a.jar", OperationType.INSTALL_OBJECT, OBJECT_HASH, 1, null)),
				List.of(new UpdatePlan.ProjectedFile(Root.PROJECTION, "mods/a.jar", true, OBJECT_HASH, 1)), new ClientConfigJsons.ClientConfigFieldsV3(),
				Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), List.of(), List.of(), List.of(),
				List.of(new UpdatePlan.NestedCopy("mods/nested.jar", OBJECT_HASH, 2, Set.of("sodium"))), consequences);
		UpdateTransaction transaction = UpdateTransaction.createRemoval(plan, ClientPlatform.LINUX, null, ledger.toFields(), "", new ClientConfigJsons.ClientConfigFieldsV3());

		Path file = temporaryDirectory.resolve("update-transaction.json");
		ConfigTools.writeAtomic(file, transaction);

		UpdateTransaction roundTripped = UpdateTransaction.read(file);

		assertNotNull(roundTripped);
		assertEquals(UpdateTransaction.Purpose.MODPACK_REMOVAL, roundTripped.purpose);
		assertEquals("packaa1", roundTripped.plan().modpackId());
		assertEquals(ledger.digest(), roundTripped.plan().packTarget().ledgerDigest());
		assertEquals(Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), roundTripped.plan().restartReasons());
		assertEquals("mods/a.jar", roundTripped.plan().operations().get(0).relativePath());
		assertEquals(OBJECT_HASH, roundTripped.plan().operations().get(0).expectedObjectHash());
		assertEquals("mods/nested.jar", roundTripped.plan().generatedCopies().get(0).relativePath());
		// Service ids are inspection-only and are not part of the durable plan.
		assertTrue(roundTripped.plan().generatedCopies().get(0).ids().isEmpty());
		assertEquals(ChangeSet.Kind.ADDED, roundTripped.plan().consequences().changes().get(0).kind());
	}
}
