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
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
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
	void anAppliedPrefixOfTheSameOutcomeStaysCompatible() {
		ChangeSet reviewedConsequences = ChangeSet.of(new ChangeSet.Change("mods/a.jar", ChangeSet.Kind.ADDED,
				List.of(new ChangeSet.Occurrence("PROJECTION", "mods/a.jar", 1, null, null, OBJECT_HASH, "mod", List.of(), List.of()))));
		UpdatePlan reviewed = plan(
				List.of(operation("mods/a.jar", OBJECT_HASH), operation("config/a.json", OTHER_HASH)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/a.jar", true, OBJECT_HASH, 1)),
				reviewedConsequences);
		// The first apply already ran the config operation, so the rebuilt plan carries less work for the same outcome.
		UpdatePlan replanned = plan(
				List.of(operation("mods/a.jar", OBJECT_HASH)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/a.jar", true, OBJECT_HASH, 1)),
				ChangeSet.empty());

		ReviewedUpdatePlan.pending(reviewed).requireCompatible(replanned);
	}

	@Test
	void aDriftedProjectedFinalStateCannotBypassReview() {
		ReviewedUpdatePlan reviewed = ReviewedUpdatePlan.pending(plan(
				List.of(operation("mods/a.jar", OBJECT_HASH)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/a.jar", true, OBJECT_HASH, 1))));
		UpdatePlan drifted = plan(
				List.of(operation("mods/a.jar", OBJECT_HASH)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/a.jar", true, OTHER_HASH, 1)));

		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> reviewed.requireCompatible(drifted));
		assertTrue(failure.getMessage().contains("projected final state"));
	}

	@Test
	void aDriftedPlannedClientConfigCannotBypassReview() {
		ClientConfigJsons.ClientConfigFieldsV3 config = new ClientConfigJsons.ClientConfigFieldsV3();
		config.playMusic = false;

		ReviewedUpdatePlan reviewed = ReviewedUpdatePlan.pending(plan(List.of(operation("mods/a.jar", OBJECT_HASH)), List.of(), new ClientConfigJsons.ClientConfigFieldsV3()));
		UpdatePlan drifted = plan(List.of(operation("mods/a.jar", OBJECT_HASH)), List.of(), config);

		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> reviewed.requireCompatible(drifted));
		assertTrue(failure.getMessage().contains("planned client configuration"));
	}

	@Test
	void durableTransactionUsesTheSameExecutionFingerprint() {
		UpdatePlan plan = plan(List.of(operation("mods/a.jar", OBJECT_HASH)));
		UpdateTransaction transaction = new UpdateTransaction();
		transaction.modpackId = plan.modpackId();
		transaction.contentToken = plan.packTarget().contentToken();
		transaction.policySha1 = plan.packTarget().policySha1();
		transaction.ledgerDigest = plan.packTarget().ledgerDigest();
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
		return plan(operations, List.of(), new ClientConfigJsons.ClientConfigFieldsV3());
	}

	private static UpdatePlan plan(List<Operation> operations, List<ProjectedFile> projected) {
		return plan(operations, projected, new ClientConfigJsons.ClientConfigFieldsV3());
	}

	private static UpdatePlan plan(List<Operation> operations, List<ProjectedFile> projected, ChangeSet consequences) {
		return plan(operations, projected, new ClientConfigJsons.ClientConfigFieldsV3(), consequences);
	}

	private static UpdatePlan plan(List<Operation> operations, List<ProjectedFile> projected, ClientConfigJsons.ClientConfigFieldsV3 config) {
		return plan(operations, projected, config, ChangeSet.empty());
	}

	private static UpdatePlan plan(List<Operation> operations, List<ProjectedFile> projected, ClientConfigJsons.ClientConfigFieldsV3 config, ChangeSet consequences) {
		return new UpdatePlan("packaa1", new PackTarget("packaa1", "a".repeat(40), "b".repeat(40), "c".repeat(40)), operations, projected,
				config, Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), List.of(), List.of(), List.of(), List.of(), consequences);
	}

	private static Operation operation(String path, String objectHash) {
		return new Operation(Root.PROJECTION, path, OperationType.INSTALL_OBJECT, objectHash, 1, null);
	}
}
