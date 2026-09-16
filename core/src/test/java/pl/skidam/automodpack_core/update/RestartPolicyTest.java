package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;

class RestartPolicyTest {

	@Test
	void preloadRequiresOnlyHotLoadBlockers() {
		assertEquals(RestartDemand.NONE, RestartPolicy.atPreload(Set.of()));
		assertEquals(RestartDemand.NONE, RestartPolicy.atPreload(Set.of(RestartReason.CHANGED_GROUP_SELECTION, RestartReason.SELECTED_MODPACK)));
		assertEquals(RestartDemand.REQUIRED, RestartPolicy.atPreload(Set.of(RestartReason.CHANGED_LOADER_VERSION)));
		assertEquals(RestartDemand.REQUIRED, RestartPolicy.atPreload(Set.of(RestartReason.CHANGED_GROUP_SELECTION, RestartReason.REMOVED_STANDARD_MODS)));
	}

	@Test
	void inGameForcesModsAndOffersOtherFiles() {
		assertEquals(RestartDemand.NONE, RestartPolicy.inGame(Set.of(), List.of()));
		assertEquals(RestartDemand.NONE, RestartPolicy.inGame(Set.of(RestartReason.SELECTED_MODPACK), List.of()));
		assertEquals(RestartDemand.REQUIRED, RestartPolicy.inGame(Set.of(RestartReason.CORRECTED_FILE_LOCATIONS), List.of("config/a.toml")));
		assertEquals(RestartDemand.REQUIRED, RestartPolicy.inGame(Set.of(), List.of("mods/new.jar")));
		assertEquals(RestartDemand.OFFERED, RestartPolicy.inGame(Set.of(), List.of("config/a.toml")));
		assertEquals(RestartDemand.OFFERED, RestartPolicy.inGame(Set.of(RestartReason.CHANGED_GROUP_SELECTION), List.of("resourcepacks/pack.zip")));
	}
}
