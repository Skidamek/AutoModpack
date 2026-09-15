package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.update.UpdatePlan;

class RestartDecisionTest {

	@Test
	void selectionNarrationAloneDoesNotDemandRestart() {
		assertFalse(new RestartDecision.ApplyResult(Set.of(UpdatePlan.RestartReason.CHANGED_GROUP_SELECTION)).requiresRestart());
		assertFalse(new RestartDecision.ApplyResult(Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK)).requiresRestart());
	}

	@Test
	void bootCriticalReasonsDemandRestart() {
		assertTrue(new RestartDecision.ApplyResult(Set.of(UpdatePlan.RestartReason.CORRECTED_FILE_LOCATIONS)).requiresRestart());
		assertTrue(new RestartDecision.ApplyResult(Set.of(UpdatePlan.RestartReason.CHANGED_LOADER_VERSION)).requiresRestart());
		assertTrue(new RestartDecision.ApplyResult(Set.of(UpdatePlan.RestartReason.CHANGED_GROUP_SELECTION, UpdatePlan.RestartReason.REMOVED_STANDARD_MODS)).requiresRestart());
	}

	@Test
	void noReasonsDemandsNoRestart() {
		assertFalse(new RestartDecision.ApplyResult(Set.of()).requiresRestart());
	}
}
