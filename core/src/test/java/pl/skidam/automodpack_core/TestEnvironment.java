package pl.skidam.automodpack_core;

import java.nio.file.Path;

import pl.skidam.automodpack_core.loader.LoaderManagerService.EnvironmentType;
import pl.skidam.automodpack_core.loader.LoaderManagerService.ModPlatform;

/** Installs a process environment for a test; no loader exists there, so the side stays unknown unless the test asks for one. */
public final class TestEnvironment {
	private TestEnvironment() {}

	public static Environment swap(String loader, String mcVersion, String modVersion, String loaderVersion) {
		return swap(null, ModPlatform.FABRIC, loader, mcVersion, loaderVersion, modVersion, Path.of("automodpack-test.jar"));
	}

	/** Returns the environment that was installed before, so the test can put it back in a finally. */
	public static Environment swap(EnvironmentType side, ModPlatform platform, String loader, String mcVersion, String loaderVersion, String modVersion, Path thisModJar) {
		Environment previous = Environment.installed();
		Environment.install(side, platform, loader, mcVersion, loaderVersion, modVersion, false, thisModJar);
		return previous;
	}

	public static void restore(Environment previous) {
		Environment.restore(previous);
	}
}
