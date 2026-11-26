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
	void preloadAbsorbsStandardModsMutationsOnlyWhenLoaderRunsBeforeDiscovery() {
		for (RestartReason reason : RestartReason.values()) {
			RestartDemand withoutPreDiscovery = switch (reason) {
				case CHANGED_GROUP_SELECTION, SELECTED_MODPACK -> RestartDemand.NONE;
				case REMOVED_LOCAL_MODS, CORRECTED_FILE_LOCATIONS, FIXED_NESTED_MODS, REMOVED_DUPLICATE_MODS, REMOVED_STANDARD_MODS,
						CHANGED_LOADER_VERSION, CHANGED_LOADER_TYPE, CHANGED_GAME_VERSION, MANUAL_VERSION_SWITCH ->
					RestartDemand.REQUIRED;
			};
			RestartDemand withPreDiscovery = switch (reason) {
				case CHANGED_GROUP_SELECTION, SELECTED_MODPACK, REMOVED_LOCAL_MODS, CORRECTED_FILE_LOCATIONS, FIXED_NESTED_MODS,
						REMOVED_DUPLICATE_MODS, REMOVED_STANDARD_MODS ->
					RestartDemand.NONE;
				case CHANGED_LOADER_VERSION, CHANGED_LOADER_TYPE, CHANGED_GAME_VERSION, MANUAL_VERSION_SWITCH -> RestartDemand.REQUIRED;
			};
			assertEquals(withoutPreDiscovery, RestartPolicy.atPreload(Set.of(reason), false), reason.name() + " without pre-discovery");
			assertEquals(withPreDiscovery, RestartPolicy.atPreload(Set.of(reason), true), reason.name() + " with pre-discovery");
		}
		assertEquals(RestartDemand.REQUIRED, RestartPolicy.atPreload(Set.of(RestartReason.REMOVED_STANDARD_MODS, RestartReason.CHANGED_LOADER_VERSION), true));
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
