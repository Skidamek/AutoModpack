package pl.skidam.automodpack_core.loader;

import java.util.Set;

public interface ModpackLoaderService {
	void loadModpack(ModpackLoadRequest request);

	/**
	 * Service files (paths under {@code META-INF/services/}) this loader generation cannot host in
	 * place - a modpack mod shipping any of these must be copied into the standard {@code mods/}
	 * directory instead of being loaded from the active projection. The default is none.
	 */
	default Set<String> forceCopyServices() {
		return Set.of();
	}

	/** Whether this loader discovers nested jars that conflict with {@code mods/} and must be copied out. */
	default boolean discoversNestedConflicts() {
		return false;
	}
}
