package pl.skidam.automodpack_core.launchers;

import java.io.IOException;
import java.util.EnumSet;

/**
 * One supported launcher's instance metadata, found by probing the file layout around the game directory.
 * Implementations read and write the launcher's own version records; the game never launches itself, so every
 * write only has to be in place before the player's next manual launch.
 */
public interface LauncherAdapter {

	/** Whether the game directory sits inside this launcher's instance layout. */
	boolean detected();

	/**
	 * The version axes this launcher's metadata still needs to switch to run the target pack, read from its own records. Throws when the instance file is present but unreadable, so the switch refuses instead of looking
	 * already matched.
	 */
	EnumSet<LauncherVersionSwapper.Axis> requiredAxes(String targetLoader, String targetLoaderVersion, String targetMcVersion) throws IOException;

	/**
	 * Writes the axes and re-reads the launcher's records: returns only once the metadata converged to the target,
	 * otherwise throws. The target loader is one of fabric/forge/neoforge; the version strings are the mod's own
	 * unprefixed loader version and Minecraft version.
	 */
	void apply(EnumSet<LauncherVersionSwapper.Axis> axes, String targetLoader, String targetLoaderVersion, String targetMcVersion) throws IOException;
}
