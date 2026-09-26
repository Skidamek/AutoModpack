package pl.skidam.automodpack_core.update;

import java.util.Collection;
import java.util.Set;

import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;

/**
 * Restart consequences of one plan: preload and in-game ask the same three-way question. Preload never
 * offers - configs land on disk for the next read, and projection mods hot-load - so it only returns
 * {@link RestartDemand#REQUIRED} or {@link RestartDemand#NONE}.
 */
public final class RestartPolicy {
	private RestartPolicy() {}

	/** Preload: only a change this boot cannot absorb (loader version, vanilla {@code mods/}) forces a relaunch. */
	public static RestartDemand atPreload(Set<RestartReason> reasons) {
		if (reasons == null) return RestartDemand.NONE;
		for (RestartReason reason : reasons) if (reason.blocksHotLoad()) return RestartDemand.REQUIRED;
		return RestartDemand.NONE;
	}

	/**
	 * In-game: mods and loader-state cannot load at runtime, so those force a relaunch. Other file changes
	 * are offered because a running game may not re-read them. Selection narration with no file work is none.
	 */
	public static RestartDemand inGame(Set<RestartReason> reasons, Collection<String> changedOrRemovedPaths) {
		if (atPreload(reasons) == RestartDemand.REQUIRED) return RestartDemand.REQUIRED;
		boolean offered = false;
		if (changedOrRemovedPaths != null) {
			for (String path : changedOrRemovedPaths) {
				if (path == null || path.isBlank()) continue;
				if (ModpackPathPolicy.isModPath(path)) return RestartDemand.REQUIRED;
				offered = true;
			}
		}
		return offered ? RestartDemand.OFFERED : RestartDemand.NONE;
	}
}
