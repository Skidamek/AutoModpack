package pl.skidam.automodpack_core.loader;

public interface LoaderManagerService {
	enum ModPlatform {
		FABRIC, FORGE, NEOFORGE
	}
	/** The side this JVM runs as; a mod's own declared side is {@code ModEnvironment}. */
	enum EnvironmentType {
		CLIENT, SERVER, UNIVERSAL
	}

	ModPlatform getPlatformType();

	boolean isModLoaded(String modId);

	String getLoaderVersion();

	EnvironmentType getEnvironmentType();

	boolean isDevelopmentEnvironment();

	String getModVersion(String modId);

	/** True when this loader runs {@code Preload} before scanning {@code mods/} for regular mod candidates. */
	default boolean runsBeforeModsDiscovery() {
		return false;
	}
}
