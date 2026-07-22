package pl.skidam.automodpack_loader_core_neoforge;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.utils.FileInspection;

/**
 * NeoForge 21.6+ ("FML 10.x"/"11.x") drops ModLauncher/securejarhandler entirely: early-service
 * jars are chained onto ONE flat {@code URLClassLoader} lineage that {@code FMLLoader} owns and
 * grows via its private {@code appendLoader(name, List<JarContents>)}. Because
 * {@code FMLLoader.buildTransformingLoader()} always does
 * {@code gameLoader.setFallbackClassLoader(currentClassLoader)}, growing that same chain with a
 * modpack-folder jar's classes BEFORE the game loader is built makes them resolve through that
 * native fallback with no manual class/resource bridging, {@code addReads}, or {@code Unsafe}.
 *
 * <p>
 * This class inspects and registers modpack-folder early-service jars. Once appended, FML discovers
 * their locators, readers, language loaders, and class processors through its native ServiceLoader
 * passes; AutoModpack only invokes GraphicsBootstrapper early because that pass already happened.
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
	// ICoreMod and ModLauncher's ITransformationService no longer exist as SPIs on this loader.

	// Every service FML can discover natively after the jar is appended to its flat classloader
	// chain. GraphicsBootstrapper is the sole exception: AutoModpack invokes it because FML's own
	// bootstrapper pass has already selected AutoModpack by then.
	public static final Set<String> HANDLEABLE_SERVICES = Set.of(GRAPHICS_BOOTSTRAPPER_SERVICE, CANDIDATE_LOCATOR_SERVICE, DEPENDENCY_LOCATOR_SERVICE,
			MOD_FILE_READER_SERVICE, LANGUAGE_LOADER_SERVICE, CLASS_PROCESSOR_SERVICE, CLASS_PROCESSOR_PROVIDER_SERVICE);

	// Read from net.neoforged.fml.loading.EarlyServiceDiscovery.SERVICES so this is exact for this
	// loader version. The force-copy decision (ModpackLoader#knownServices) counts only services
	// here: one this loader doesn't handle can't be fixed by copying to standard mods/ either.
	private static final Set<String> HANDLED_SERVICES = computeHandledServices();

	/**
	 * Service files this loader version actually discovers/runs; superset of {@link #HANDLEABLE_SERVICES}.
	 * Used to narrow a jar's raw service set (see {@link #inspect}) so a legacy/removed SPI this
	 * loader version doesn't handle can't wrongly block {@link #eligibleForInPlace}.
	 */
	private static Set<String> knownServices() {
		return HANDLED_SERVICES;
	}

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

	// Modpack-folder jars EarlyServiceBootstrapper has appended to FMLLoader's classloader chain,
	// plus that shared classloader.
	private static final Set<Path> REGISTERED_JARS = ConcurrentHashMap.newKeySet();

	static void register(List<Path> jars) {
		for (Path jar : jars) {
			REGISTERED_JARS.add(canonical(jar));
		}
	}

	public static boolean isEarlyServiceJar(Path jar) {
		return jar != null && REGISTERED_JARS.contains(canonical(jar));
	}

	// Purely lexical (no toRealPath()): both the writer (register(), from Files.list(modpackMods))
	// and every reader derive their paths from listing the same modpack mods/ folder, with no
	// symlink indirection between them, so lexical equality already holds.
	private static Path canonical(Path jar) {
		return jar.toAbsolutePath().normalize();
	}

	// Per-jar facts derived from a single jar mount, cached for the JVM's life.
	private record JarInfo(boolean eligible, Map<String, List<String>> serviceImpls, boolean standalone) {}

	private static final Map<Path, JarInfo> JAR_INFO = new ConcurrentHashMap<>();

	private static JarInfo info(Path jar) {
		return JAR_INFO.computeIfAbsent(canonical(jar), EarlyServiceLayer::inspect);
	}

	private static JarInfo inspect(Path jar) {
		boolean eligible = false;
		Map<String, List<String>> impls = new HashMap<>();
		boolean standalone = false;
		try (FileSystem fs = FileSystems.newFileSystem(jar)) {
			// Scoped to what this loader version actually handles (knownServices()), so a legacy/
			// removed SPI doesn't wrongly make an otherwise in-place-able mod look unhandleable.
			Set<String> services = FileInspection.getServices(fs, knownServices());
			eligible = !services.isEmpty() && HANDLEABLE_SERVICES.containsAll(services);
			standalone = Files.exists(fs.getPath("META-INF/neoforge.mods.toml")) && !FileInspection.hasNestedModWithSameId(fs);
			if (Files.exists(fs.getPath(GRAPHICS_BOOTSTRAPPER_SERVICE))) {
				impls.put(GRAPHICS_BOOTSTRAPPER_SERVICE, readServiceImpls(fs, GRAPHICS_BOOTSTRAPPER_SERVICE));
			}
		} catch (Exception e) {
			LOGGER.warn("[AutoModpack] Could not inspect {}; not handling it in place", jar.getFileName(), e);
		}
		return new JarInfo(eligible, impls, standalone);
	}

	/** The implementation class names of a service declared at the jar's root. */
	public static List<String> serviceImpls(Path jar, String serviceFile) {
		return info(jar).serviceImpls().getOrDefault(serviceFile, List.of());
	}

	public static boolean eligibleForInPlace(Path jar) {
		return info(jar).eligible();
	}

	public static boolean isStandaloneModFile(Path jar) {
		return info(jar).standalone();
	}

	private static List<String> readServiceImpls(FileSystem fs, String serviceFile) {
		List<String> impls = new ArrayList<>();
		Path service = fs.getPath(serviceFile);
		if (!Files.exists(service)) return impls;
		try (InputStream is = Files.newInputStream(service); BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int comment = line.indexOf('#');
				if (comment >= 0) line = line.substring(0, comment);
				line = line.trim();
				if (!line.isEmpty()) impls.add(line);
			}
		} catch (Exception e) {
			LOGGER.error("[AutoModpack] Failed to read {}", serviceFile, e);
		}
		return impls;
	}
}
