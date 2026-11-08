package pl.skidam.automodpack_core;

import java.nio.file.Path;

import pl.skidam.automodpack_core.loader.LoaderManagerService.EnvironmentType;
import pl.skidam.automodpack_core.loader.LoaderManagerService.ModPlatform;

/**
 * The process facts every flow needs: which side this JVM is, which loader and versions it runs, where this mod's jar
 * sits, and which phase the process is in.
 * Installed once by {@link Preload}; the facts never change afterwards, so a reader cannot observe a half-seeded
 * environment. Reading the facts before the install throws instead of returning null.
 */
public final class Environment {
	public enum Phase {
		/** Preload has not started; the loader's own early-launch environment is still the only source of facts. */
		NOT_STARTED,
		/** Preload has started and the game has not taken over yet. */
		PRELAUNCH,
		/** The loader entrypoints ran; the game owns the process from here on. */
		GAME
	}

	private static volatile Phase phase = Phase.NOT_STARTED;
	private static volatile Environment current;

	private final EnvironmentType side;
	private final ModPlatform platform;
	private final String loader;
	private final String mcVersion;
	private final String loaderVersion;
	private final String modVersion;
	private final boolean development;
	private final Path thisModJar;
	private final long preloadTime;

	private Environment(EnvironmentType side, ModPlatform platform, String loader, String mcVersion, String loaderVersion, String modVersion, boolean development, Path thisModJar, long preloadTime) {
		this.side = side;
		this.platform = platform;
		this.loader = loader;
		this.mcVersion = mcVersion;
		this.loaderVersion = loaderVersion;
		this.modVersion = modVersion;
		this.development = development;
		this.thisModJar = thisModJar;
		this.preloadTime = preloadTime;
	}

	/** Marks the start of prelaunch, before the facts are gathered: loaders read the phase while {@link Preload} asks them for versions. */
	public static void beginPrelaunch() {
		phase = Phase.PRELAUNCH;
	}

	/**
	 * The composition root's one install. The side is nullable: a detached helper or a test runs without a loader and must
	 * keep resolving data roots the way a loaderless process always has.
	 */
	static void install(EnvironmentType side, ModPlatform platform, String loader, String mcVersion, String loaderVersion, String modVersion, boolean development, Path thisModJar) {
		current = new Environment(side, platform, loader, mcVersion, loaderVersion, modVersion, development, thisModJar, System.currentTimeMillis());
	}

	/** Test-only sibling of {@link #install}: restores the environment a test captured before it installed its own. */
	static void restore(Environment previous) {
		current = previous;
	}

	/** Called by the loader entrypoints once the game owns the process; the phase never goes back. */
	public static void markGameStarted() {
		phase = Phase.GAME;
	}

	/** True from the start of prelaunch until the loader entrypoints take over, including before the facts are installed. */
	public static boolean isPrelaunching() {
		return phase == Phase.PRELAUNCH;
	}

	public static Phase phase() {
		return phase;
	}

	public static Environment current() {
		Environment environment = current;
		if (environment == null) throw new IllegalStateException("The AutoModpack environment is not installed yet: Preload must run before anything reads the process environment");
		return environment;
	}

	/** The installed environment, or null before {@link Preload} runs: detached helpers and tests without a loader. */
	public static Environment installed() {
		return current;
	}

	/** The process side, or null when no loader environment exists; the data-root resolver and client storage accept that. */
	public static EnvironmentType sideOrNull() {
		Environment environment = current;
		return environment == null ? null : environment.side;
	}

	public EnvironmentType side() {
		return side;
	}

	public boolean isServer() {
		return side == EnvironmentType.SERVER;
	}

	public boolean isClient() {
		return side == EnvironmentType.CLIENT;
	}

	public ModPlatform platform() {
		return platform;
	}

	public String loader() {
		return loader;
	}

	public String mcVersion() {
		return mcVersion;
	}

	public String loaderVersion() {
		return loaderVersion;
	}

	public String modVersion() {
		return modVersion;
	}

	public boolean development() {
		return development;
	}

	public Path thisModJar() {
		return thisModJar;
	}

	public long preloadTime() {
		return preloadTime;
	}
}
