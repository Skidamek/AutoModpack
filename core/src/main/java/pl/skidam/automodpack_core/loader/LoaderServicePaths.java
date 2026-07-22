package pl.skidam.automodpack_core.loader;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every {@code META-INF/services/...} path AutoModpack recognizes, declared exactly once. Forge
 * and NeoForge's {@code EarlyServiceLayer}s and {@link pl.skidam.automodpack_core.utils.FileInspection}
 * compose their own working sets from these constants instead of repeating the literal strings.
 */
public final class LoaderServicePaths {

	private LoaderServicePaths() {}

	// Shared: ModLauncher boot-layer service, discovered before mod discovery on forge and neoforge alike.
	public static final String TRANSFORMATION_SERVICE = "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";

	// Forge (net.minecraftforge.forgespi)
	public static final String FORGE_MOD_LOCATOR = "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator";
	public static final String FORGE_DEPENDENCY_LOCATOR = "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator";
	public static final String FORGE_LANGUAGE_PROVIDER = "META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider";

	// NeoForge (net.neoforged.neoforgespi)
	public static final String NEOFORGE_MOD_LOCATOR = "META-INF/services/net.neoforged.neoforgespi.locating.IModLocator"; // pre-FML10; removed after
	public static final String NEOFORGE_DEPENDENCY_LOCATOR = "META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator";
	public static final String NEOFORGE_MOD_FILE_READER = "META-INF/services/net.neoforged.neoforgespi.locating.IModFileReader";
	public static final String NEOFORGE_LANGUAGE_LOADER = "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader";
	public static final String NEOFORGE_CANDIDATE_LOCATOR = "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator";
	public static final String NEOFORGE_GRAPHICS_BOOTSTRAPPER = "META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper";
	public static final String NEOFORGE_CLASS_PROCESSOR = "META-INF/services/net.neoforged.neoforgespi.transformation.ClassProcessor";
	public static final String NEOFORGE_CLASS_PROCESSOR_PROVIDER = "META-INF/services/net.neoforged.neoforgespi.transformation.ClassProcessorProvider";
	public static final String NEOFORGE_IMMEDIATE_WINDOW_PROVIDER = "META-INF/services/net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider";
	public static final String NEOFORGE_COREMOD = "META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod";

	// Broad, cross-version set of Forge service files, used to tell a service mod apart from a
	// plain mod before the running loader/version is known.
	public static final Set<String> FORGE_SERVICES = Set.of(FORGE_MOD_LOCATOR, FORGE_DEPENDENCY_LOCATOR, FORGE_LANGUAGE_PROVIDER, TRANSFORMATION_SERVICE);

	// Broad, cross-version set of NeoForge service files, same purpose as FORGE_SERVICES.
	public static final Set<String> NEOFORGE_SERVICES = Set.of(NEOFORGE_MOD_LOCATOR, NEOFORGE_DEPENDENCY_LOCATOR, NEOFORGE_MOD_FILE_READER,
			NEOFORGE_LANGUAGE_LOADER, NEOFORGE_CANDIDATE_LOCATOR, NEOFORGE_GRAPHICS_BOOTSTRAPPER, NEOFORGE_CLASS_PROCESSOR,
			NEOFORGE_CLASS_PROCESSOR_PROVIDER,
			NEOFORGE_IMMEDIATE_WINDOW_PROVIDER, NEOFORGE_COREMOD, TRANSFORMATION_SERVICE);

	/** Union of every recognized service path, loader-agnostic. */
	public static final Set<String> ALL_SERVICES = Stream.concat(FORGE_SERVICES.stream(), NEOFORGE_SERVICES.stream()).collect(Collectors.toUnmodifiableSet());
}
