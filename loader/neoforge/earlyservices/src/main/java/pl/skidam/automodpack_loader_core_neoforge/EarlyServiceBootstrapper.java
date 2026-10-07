package pl.skidam.automodpack_loader_core_neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.VersionInfo;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.Preload;
import pl.skidam.automodpack_core.loader.EarlyLaunchEnvironment;
import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.TargetId;
import pl.skidam.automodpack_loader_core_neoforge.loader.LoaderManager;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

/**
 * Cross-generation linkage: the universal outer jar registers this class and the fml4 bootstrapper
 * under the same GraphicsBootstrapper service. That interface declares the identical abstract pair
 * {@code name()}/{@code bootstrap(String[])} in neoforgespi 4.x, 10.x and 11.x, and this class's only
 * supertype beyond it is Object - so it loads on every NeoForge generation, and the
 * {@link GenerationProbes#NEOFORGE_EARLYSERVICES} guard no-ops it wherever the ModLauncher-era
 * generation runs.
 *
 * <p>
 * "Loads on every generation" is about LINKAGE, not just execution: verification resolves the types
 * named in a class's own bytecode, so an {@code if}-guard cannot protect generation-specific
 * linkage - everything generation-specific lives in {@link EarlyServiceLayer}, which only this
 * generation executes into and therefore ever links.
 *
 * <p>
 * FML 10/11 strips {@code --fml.mcVersion} into {@link VersionInfo} before this callback, so the
 * bootstrap argument list does not carry it. Installer launches have those fields already; userdev
 * leaves them empty until GameLocator, and the Minecraft jar is already on the classpath with
 * {@code version.json} - the same file GameLocator reads.
 */
public class EarlyServiceBootstrapper implements GraphicsBootstrapper {

	@Override
	public String name() {
		return "automodpack";
	}

	@Override
	public void bootstrap(String[] arguments) {
		if (!GenerationProbes.NEOFORGE_EARLYSERVICES) return;

		FMLLoader loader = FMLLoader.getCurrent();
		EarlyLaunchEnvironment.IS_CLIENT = loader.getDist() == Dist.CLIENT;
		VersionInfo versions = loader.getVersionInfo();
		EarlyLaunchEnvironment.MC_VERSION = versions.mcVersion();
		EarlyLaunchEnvironment.LOADER_VERSION = versions.neoForgeVersion();
		if (EarlyLaunchEnvironment.MC_VERSION == null) {
			EarlyLaunchEnvironment.MC_VERSION = classpathMinecraftVersion();
			// The version.json that supplied it is whichever jar's came first on the classpath, and a wrong id resolves to a different target silently.
			Constants.LOGGER.warn("[AutoModpack] No --fml.mcVersion given; taking the Minecraft version from the classpath as {}", EarlyLaunchEnvironment.MC_VERSION);
		}

		TargetId.id("neoforge", EarlyLaunchEnvironment.MC_VERSION);

		ProgressMeter progress = StartupNotificationManager.prependProgressBar("[Automodpack] Preload", 0);
		try {
			new Preload(new LoaderManager(), new ModpackLoader());
		} finally {
			progress.complete();
		}

		try {
			if (Constants.LOADER_MANAGER.getEnvironmentType() != LoaderManagerService.EnvironmentType.CLIENT) return;
			List<Path> earlyServiceJars = ModpackLoader.modsToLoad.stream().filter(EarlyServiceLayer::eligibleForInPlace).toList();
			if (earlyServiceJars.isEmpty()) return;
			Constants.LOGGER.info("[AutoModpack] Bootstrapping {} early-service mod(s) from the active projection in place", earlyServiceJars.size());
			EarlyModLocator.hostInPlace(earlyServiceJars, arguments);
		} catch (Throwable t) {
			Constants.LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
			throw new RuntimeException("AutoModpack early-service bootstrap failed", t);
		}
	}

	private static String classpathMinecraftVersion() {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		if (classLoader == null) classLoader = EarlyServiceBootstrapper.class.getClassLoader();
		try (InputStream in = classLoader.getResourceAsStream("version.json")) {
			if (in == null) return null;
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			return json.getAsJsonPrimitive("id").getAsString();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
