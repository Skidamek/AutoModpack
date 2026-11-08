package pl.skidam.automodpack_core.loader;

/** The services the platform side hands to core: loading a modpack at runtime, and asking the game about a player. */
public final class LoaderServices {
	private static volatile ModpackLoaderService modpackLoader;
	private static volatile GameCallService gameCall = new NullGameCall();

	private LoaderServices() {}

	public static void installModpackLoader(ModpackLoaderService loader) {
		modpackLoader = loader;
	}

	public static void installGameCall(GameCallService call) {
		gameCall = call;
	}

	/** The loader's runtime modpack loader; reading it before the platform installs it is a bug. */
	public static ModpackLoaderService modpackLoader() {
		ModpackLoaderService loader = modpackLoader;
		if (loader == null) throw new IllegalStateException("The modpack loader is not installed yet: the platform side must install it during prelaunch");
		return loader;
	}

	/** The installed loader, or null before the platform installs one: tests run without a loader. */
	public static ModpackLoaderService modpackLoaderOrNull() {
		return modpackLoader;
	}

	/** The game's authorization answers; a process without a game side answers through the null implementation. */
	public static GameCallService gameCall() {
		return gameCall;
	}
}
