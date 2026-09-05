package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

/** Restart policy for an applied client plan: restart reasons as player-facing descriptions, restart types per flow, and the restart-loop fingerprint. */
final class RestartDecision {
	private RestartDecision() {}

	/** The restart consequences of one applied plan. */
	record ApplyResult(Set<UpdatePlan.RestartReason> restartReasons) {
		ApplyResult {
			restartReasons = restartReasons.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(restartReasons));
		}

		boolean requiresRestart() {
			return !restartReasons.isEmpty();
		}

		List<String> reasonIds() {
			return restartReasons.stream().map(Enum::name).toList();
		}

		List<String> reasonDescriptions() {
			return restartReasons.stream().map(RestartDecision::describe).toList();
		}
	}

	static ApplyResult applyResult(UpdatePlan plan) {
		return new ApplyResult(plan.restartReasons());
	}

	/** Player-facing description of one restart reason, shown with the changelogs. */
	static String describe(UpdatePlan.RestartReason reason) {
		return switch (reason) {
			case REMOVED_NON_MODPACK_FILES -> "files removed from the modpack were deleted from the game directory";
			case REMOVED_LOCAL_MODS -> "player-approved local mods were preserved and removed from the game directory";
			case CORRECTED_FILE_LOCATIONS -> "standard-directory mods were copied or updated";
			case FIXED_NESTED_MODS -> "conflicting nested mods were copied to the standard mods directory";
			case REMOVED_DUPLICATE_MODS -> "duplicate standard-directory mods were removed";
			case REMOVED_STANDARD_MODS -> "modpack-owned mods were removed from the standard mods directory";
			case APPLIED_SERVER_DELETIONS -> "server-requested mod deletions were applied";
			case CHANGED_LOADER_VERSION -> "launcher loader-version metadata changed";
			case CHANGED_GROUP_SELECTION -> "the selected modpack groups changed";
			case SELECTED_MODPACK -> "the selected stable modpack changed";
		};
	}

	/**
	 * Restart type for the launch-time apply restart. Deliberately keyed on {@code firstConnection}: a first install
	 * always restarts as a full download, and only then does a changed stable modpack selection count as a select.
	 */
	static UpdateType launchRestartType(boolean firstConnection, Set<UpdatePlan.RestartReason> reasons) {
		return firstConnection ? UpdateType.FULL : reasons.contains(UpdatePlan.RestartReason.SELECTED_MODPACK) ? UpdateType.SELECT : UpdateType.UPDATE;
	}

	/**
	 * Restart type for a post-apply restart. Deliberately keyed on {@code fullDownload} instead of
	 * {@code firstConnection}; the launch-time flow answers with {@link #launchRestartType}, and the two conditions
	 * stay separate until unifying them is an explicitly made decision.
	 */
	static UpdateType applyRestartType(boolean fullDownload, Set<UpdatePlan.RestartReason> reasons) {
		return reasons.contains(UpdatePlan.RestartReason.SELECTED_MODPACK) ? UpdateType.SELECT : fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
	}

	/** Fingerprint of the applied correction state so two rapid automatic restarts for the same state can be suppressed. */
	static String stateFingerprint(ClientStorage storage, ApplyResult applyResult) {
		String contentToken;
		try {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			contentToken = state == null ? "none" : state.contentToken;
		} catch (IOException e) {
			LOGGER.warn("Cannot track rapid modpack restarts because active client state is unavailable", e);
			return null;
		}
		return String.join("\n", storage.activeDirectory().toAbsolutePath().normalize().toString(), contentToken, String.join(",", applyResult.reasonIds()));
	}
}
