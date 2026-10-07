package pl.skidam.automodpack_loader_core_neoforge;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.loader.ServiceJarIndex;

/**
 * NeoForge 21.6+ ("FML 10.x"/"11.x") drops ModLauncher/securejarhandler: early-service jars are
 * chained onto one flat {@code URLClassLoader} lineage that {@code FMLLoader} owns and grows via
 * {@code appendLoader}. Hosting happens in {@link EarlyServiceBootstrapper}, before
 * {@code ModDiscoverer} enumerates locators and readers, so those implementations load natively.
 * GraphicsBootstrappers of hosted jars are invoked from the bootstrapper because FML's own
 * bootstrapper pass is already running when we append. {@code FMLLoader.buildTransformingLoader()}
 * later does {@code gameLoader.setFallbackClassLoader(currentClassLoader)}, so game code resolves
 * these jars through that fallback with no {@code addReads} bridge.
 */
public final class EarlyServiceLayer {

	private EarlyServiceLayer() {}

	public static final String GRAPHICS_BOOTSTRAPPER_SERVICE = LoaderServicePaths.NEOFORGE_GRAPHICS_BOOTSTRAPPER;
	public static final String CANDIDATE_LOCATOR_SERVICE = LoaderServicePaths.NEOFORGE_CANDIDATE_LOCATOR;
	public static final String DEPENDENCY_LOCATOR_SERVICE = LoaderServicePaths.NEOFORGE_DEPENDENCY_LOCATOR;
	public static final String LANGUAGE_LOADER_SERVICE = LoaderServicePaths.NEOFORGE_LANGUAGE_LOADER;
	public static final String MOD_FILE_READER_SERVICE = LoaderServicePaths.NEOFORGE_MOD_FILE_READER;
	public static final String CLASS_PROCESSOR_SERVICE = LoaderServicePaths.NEOFORGE_CLASS_PROCESSOR;
	public static final String CLASS_PROCESSOR_PROVIDER_SERVICE = LoaderServicePaths.NEOFORGE_CLASS_PROCESSOR_PROVIDER;

	public static final Set<String> HANDLEABLE_SERVICES = Set.of(GRAPHICS_BOOTSTRAPPER_SERVICE, CANDIDATE_LOCATOR_SERVICE, DEPENDENCY_LOCATOR_SERVICE,
			MOD_FILE_READER_SERVICE, LANGUAGE_LOADER_SERVICE, CLASS_PROCESSOR_SERVICE, CLASS_PROCESSOR_PROVIDER_SERVICE);

	private static final Set<String> HANDLED_SERVICES = computeHandledServices();
	private static final List<String> INVOKED_SERVICES = List.of(GRAPHICS_BOOTSTRAPPER_SERVICE);

	private static Set<String> computeHandledServices() {
		Set<String> handled = new HashSet<>();
		try {
			Class<?> discovery = Class.forName("net.neoforged.fml.loading.EarlyServiceDiscovery");
			Field field = discovery.getDeclaredField("SERVICES");
			field.setAccessible(true);
			Object value = field.get(null);
			if (value instanceof Set<?> names) {
				for (Object name : names) {
					handled.add("META-INF/services/" + ((Class<?>) name).getName());
				}
			}
		} catch (Throwable t) {
			LOGGER.warn("[AutoModpack] Could not read the loader's early-service list; using the built-in fallback", t);
			handled.addAll(HANDLEABLE_SERVICES);
			handled.add(LoaderServicePaths.NEOFORGE_IMMEDIATE_WINDOW_PROVIDER);
		}
		handled.add(LANGUAGE_LOADER_SERVICE);
		addIfPresent(handled, "net.neoforged.neoforgespi.transformation.ClassProcessor", CLASS_PROCESSOR_SERVICE);
		addIfPresent(handled, "net.neoforged.neoforgespi.transformation.ClassProcessorProvider", CLASS_PROCESSOR_PROVIDER_SERVICE);
		return Set.copyOf(handled);
	}

	private static void addIfPresent(Set<String> handled, String className, String servicePath) {
		try {
			Class.forName(className, false, EarlyServiceLayer.class.getClassLoader());
			handled.add(servicePath);
		} catch (ClassNotFoundException ignored) {
			// This service does not exist on the running FML generation.
		}
	}

	private static final Set<Path> REGISTERED_JARS = ConcurrentHashMap.newKeySet();

	static void register(List<Path> jars) {
		for (Path jar : jars) REGISTERED_JARS.add(canonical(jar));
	}

	public static boolean isEarlyServiceJar(Path jar) {
		return jar != null && REGISTERED_JARS.contains(canonical(jar));
	}

	private static Path canonical(Path jar) {
		return jar.toAbsolutePath().normalize();
	}

	private static ServiceJarIndex.Facts<Boolean> facts(Path jar) {
		return ServiceJarIndex.facts(jar, HANDLED_SERVICES, INVOKED_SERVICES,
				fs -> Files.exists(fs.getPath("META-INF/neoforge.mods.toml")) && !FileInspection.hasNestedModWithSameId(fs));
	}

	public static List<String> serviceImpls(Path jar, String serviceFile) {
		return facts(jar).implsOf(serviceFile);
	}

	public static boolean eligibleForInPlace(Path jar) {
		Set<String> services = facts(jar).services();
		return !services.isEmpty() && HANDLEABLE_SERVICES.containsAll(services);
	}

	public static boolean isStandaloneModFile(Path jar) {
		return Boolean.TRUE.equals(facts(jar).extra());
	}
}
