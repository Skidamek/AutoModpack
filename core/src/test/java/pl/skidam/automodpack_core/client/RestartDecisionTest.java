package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.update.UpdatePlan;

class RestartDecisionTest {

	@Test
	void applyResultKeepsReasonIdsForTheLoopFingerprint() {
		RestartDecision.ApplyResult result = new RestartDecision.ApplyResult(Set.of(UpdatePlan.RestartReason.CHANGED_GROUP_SELECTION, UpdatePlan.RestartReason.REMOVED_STANDARD_MODS));
		assertEquals(Set.of(UpdatePlan.RestartReason.CHANGED_GROUP_SELECTION, UpdatePlan.RestartReason.REMOVED_STANDARD_MODS), result.restartReasons());
		assertEquals(2, result.reasonIds().size());
	}
}
