package pl.skidam.automodpack_core.loader;

public interface LoaderManagerService {
	enum ModPlatform {
		FABRIC, FORGE, NEOFORGE
	}
	enum EnvironmentType {
		CLIENT, SERVER, UNIVERSAL
	}

	ModPlatform getPlatformType();

	boolean isModLoaded(String modId);

	String getLoaderVersion();

	EnvironmentType getEnvironmentType();

	boolean isDevelopmentEnvironment();

	String getModVersion(String modId);
}
