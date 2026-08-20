package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

class ReviewedUpdatePlanTest {
	private static final String OBJECT_HASH = "1111111111111111111111111111111111111111";
	private static final String OTHER_HASH = "2222222222222222222222222222222222222222";

	@Test
	void reviewHasOneFiniteLifecycle() {
		ReviewedUpdatePlan reviewed = ReviewedUpdatePlan.pending(plan(List.of(operation("mods/a.jar", OBJECT_HASH))));

		assertEquals(ReviewedUpdatePlan.State.PENDING_REVIEW, reviewed.state());
		assertFalse(reviewed.isApproved());

		reviewed.approve();
		reviewed.complete();

		assertEquals(ReviewedUpdatePlan.State.APPLIED, reviewed.state());
		assertThrows(IllegalStateException.class, reviewed::cancel);
		assertThrows(IllegalStateException.class, reviewed::approve);
	}

	@Test
	void cancellationCannotBeReapprovedOrCompleted() {
		ReviewedUpdatePlan reviewed = ReviewedUpdatePlan.pending(plan(List.of()));

		reviewed.cancel();

		assertEquals(ReviewedUpdatePlan.State.CANCELLED, reviewed.state());
		assertThrows(IllegalStateException.class, reviewed::approve);
		assertThrows(IllegalStateException.class, reviewed::complete);
	}

	@Test
	void equivalentPlansHaveStableOrderIndependentFingerprint() {
		UpdatePlan first = plan(List.of(operation("mods/a.jar", OBJECT_HASH), operation("config/a.json", OTHER_HASH)));
		UpdatePlan reordered = plan(List.of(operation("config/a.json", OTHER_HASH), operation("mods/a.jar", OBJECT_HASH)));

		assertEquals(ReviewedUpdatePlan.executionDigest(first), ReviewedUpdatePlan.executionDigest(reordered));
		ReviewedUpdatePlan.pending(first).requireCompatible(reordered);
	}

	@Test
	void changedConsequencesCannotBypassReview() {
		ReviewedUpdatePlan reviewed = ReviewedUpdatePlan.pending(plan(List.of(operation("mods/a.jar", OBJECT_HASH))));
		UpdatePlan changed = plan(List.of(operation("mods/a.jar", OTHER_HASH)));

		assertThrows(IllegalStateException.class, () -> reviewed.requireCompatible(changed));
	}

	@Test
	void changedVisibleConsequencesCannotBypassReview() {
		ChangeSet firstConsequences = ChangeSet.of(new ChangeSet.Change("mods/a.jar", ChangeSet.Kind.ADDED,
				List.of(new ChangeSet.Occurrence("PROJECTION", "mods/a.jar", 1, null, OBJECT_HASH, "mod", List.of(), List.of()))));
		ChangeSet changedConsequences = ChangeSet.of(new ChangeSet.Change("mods/a.jar", ChangeSet.Kind.MODIFIED,
				List.of(new ChangeSet.Occurrence("PROJECTION", "mods/a.jar", 1, OTHER_HASH, OBJECT_HASH, "mod", List.of(), List.of()))));

		ReviewedUpdatePlan reviewed = ReviewedUpdatePlan.pending(plan(List.of(operation("mods/a.jar", OBJECT_HASH)), firstConsequences));
		UpdatePlan changed = plan(List.of(operation("mods/a.jar", OBJECT_HASH)), changedConsequences);

		assertThrows(IllegalStateException.class, () -> reviewed.requireCompatible(changed));
	}

	@Test
	void durableTransactionUsesTheSameExecutionFingerprint() {
		UpdatePlan plan = plan(List.of(operation("mods/a.jar", OBJECT_HASH)));
		UpdateTransaction transaction = new UpdateTransaction();
		transaction.modpackId = plan.modpackId();
		transaction.targetGenerationId = plan.generationTarget().targetGenerationId();
		transaction.parentGenerationId = plan.generationTarget().parentGenerationId();
		transaction.stateDigest = plan.generationTarget().stateDigest();
		transaction.ledgerDigest = plan.generationTarget().ledgerDigest();
		transaction.operations = plan.operations();
		transaction.projectedFinalState = plan.projectedFinalState();
		transaction.plannedClientConfig = plan.plannedClientConfig();
		transaction.restartReasons = List.copyOf(plan.restartReasons());
		transaction.plannedPreservations = plan.preservations();
		transaction.plannedBaselineCaptures = plan.baselineCaptures();
		transaction.plannedConflicts = plan.conflicts();
		transaction.plannedConsequencesDigest = ReviewedUpdatePlan.consequencesDigest(plan.consequences());

		assertTrue(ReviewedUpdatePlan.isCompatible(transaction, plan));
		transaction.operations = List.of(operation("mods/a.jar", OTHER_HASH));
		assertFalse(ReviewedUpdatePlan.isCompatible(transaction, plan));
	}

	private static UpdatePlan plan(List<Operation> operations) {
		return plan(operations, ChangeSet.empty());
	}

	private static UpdatePlan plan(List<Operation> operations, ChangeSet consequences) {
		return new UpdatePlan("packaa1", new GenerationTarget("packaa1", "a".repeat(40), "", "b".repeat(40), "c".repeat(40)), operations, List.of(),
				new ClientConfigJsons.ClientConfigFieldsV3(), Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), List.of(), List.of(), List.of(), List.of(), consequences);
	}

	private static Operation operation(String path, String objectHash) {
		return new Operation(Root.PROJECTION, path, OperationType.INSTALL_OBJECT, objectHash, 1, null);
	}
}
