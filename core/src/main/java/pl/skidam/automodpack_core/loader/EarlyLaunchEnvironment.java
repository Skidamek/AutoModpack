package pl.skidam.automodpack_core.loader;

import java.util.Locale;

/**
 * Launch facts every later phase reads, captured once per boot by whichever loader generation runs:
 * legacy Forge from its transformation-service {@code onLoad} (ModLauncher's stored launch arguments,
 * with the JVM's {@code sun.java.command} as fallback), NeoForge fml4 from its graphics-bootstrap
 * arguments, NeoForge fml10/11 from its {@code ILaunchContext} (no argument parsing at all). Pure
 * JDK statics on purpose: locators read these from wherever their loader loads them, so this holder
 * must never drag a loader type into a class-load.
 */
public final class EarlyLaunchEnvironment {
	/** The running Minecraft version. */
	public static volatile String MC_VERSION;
	/** The loader's own version: {@code forgeVersion} on Forge, {@code neoForgeVersion} on NeoForge. */
	public static volatile String LOADER_VERSION;
	/** Client or server, while the loader-native dist is not readable yet. */
	public static volatile Boolean IS_CLIENT;

	private EarlyLaunchEnvironment() {}

	/**
	 * Fills only the fields still null, so a preferred argument source can be captured first and a
	 * fallback merged after (legacy Forge). {@code loaderVersionArgument} is the loader's own version
	 * flag: {@code --fml.forgeVersion} or {@code --fml.neoForgeVersion}.
	 */
	public static void captureFromArguments(String[] arguments, String loaderVersionArgument) {
		if (MC_VERSION == null) MC_VERSION = argValue(arguments, "--fml.mcVersion");
		if (LOADER_VERSION == null) LOADER_VERSION = argValue(arguments, loaderVersionArgument);
		if (IS_CLIENT == null) {
			String launchTarget = argValue(arguments, "--launchTarget");
			if (launchTarget != null) IS_CLIENT = !launchTarget.toLowerCase(Locale.ROOT).contains("server");
		}
	}

	private static String argValue(String[] arguments, String name) {
		if (arguments == null) return null;
		String prefix = name + "=";
		for (int i = 0; i < arguments.length; i++) {
			if (name.equals(arguments[i]) && i + 1 < arguments.length) return arguments[i + 1];
			if (arguments[i].startsWith(prefix)) return arguments[i].substring(prefix.length());
		}
		return null;
	}
}
