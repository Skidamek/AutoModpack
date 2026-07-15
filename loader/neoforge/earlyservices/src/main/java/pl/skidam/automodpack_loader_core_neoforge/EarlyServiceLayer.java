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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import pl.skidam.automodpack_core.Constants;
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
 * This class covers this loader version's jar inspection/candidate-locator/dependency-locator/
 * mod-file-reader replay that lets a modpack-folder early-service jar's real (inner) mod load in
 * place. See {@link EarlyServiceBootstrapper} for where jars are appended to FMLLoader's chain.
 */
public final class EarlyServiceLayer {

	private EarlyServiceLayer() {}

	public static final String GRAPHICS_BOOTSTRAPPER_SERVICE = LoaderServicePaths.NEOFORGE_GRAPHICS_BOOTSTRAPPER;
	public static final String CANDIDATE_LOCATOR_SERVICE = LoaderServicePaths.NEOFORGE_CANDIDATE_LOCATOR;
	public static final String DEPENDENCY_LOCATOR_SERVICE = LoaderServicePaths.NEOFORGE_DEPENDENCY_LOCATOR;
	public static final String LANGUAGE_LOADER_SERVICE = LoaderServicePaths.NEOFORGE_LANGUAGE_LOADER;
	public static final String MOD_FILE_READER_SERVICE = LoaderServicePaths.NEOFORGE_MOD_FILE_READER;
	// ICoreMod and ModLauncher's ITransformationService no longer exist as SPIs on this loader.

	static final List<String> ACTIVELY_RUN_SERVICES = List.of(GRAPHICS_BOOTSTRAPPER_SERVICE, CANDIDATE_LOCATOR_SERVICE, DEPENDENCY_LOCATOR_SERVICE,
			MOD_FILE_READER_SERVICE);

	// Services a mod can ship without needing a copy: the actively-run ones above, plus language
	// loaders (passive - picked up from the flat classloader chain once appended).
	public static final Set<String> HANDLEABLE_SERVICES = Stream.concat(ACTIVELY_RUN_SERVICES.stream(), Stream.of(LANGUAGE_LOADER_SERVICE))
			.collect(Collectors.toUnmodifiableSet());

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
		return Set.copyOf(handled);
	}

	// Modpack-folder jars EarlyServiceBootstrapper has appended to FMLLoader's classloader chain,
	// plus that shared classloader.
	private static final Set<Path> REGISTERED_JARS = ConcurrentHashMap.newKeySet();
	private static final AtomicReference<ClassLoader> CHILD_LOADER = new AtomicReference<>();

	static void register(List<Path> jars, ClassLoader childLoader) {
		CHILD_LOADER.set(childLoader);
		for (Path jar : jars) {
			REGISTERED_JARS.add(canonical(jar));
		}
	}

	public static boolean isEarlyServiceJar(Path jar) {
		return jar != null && REGISTERED_JARS.contains(canonical(jar));
	}

	private static ClassLoader classLoaderFor(Path jar) {
		return isEarlyServiceJar(jar) ? CHILD_LOADER.get() : null;
	}

	// Purely lexical (no toRealPath()): both the writer (register(), from Files.list(modpackMods))
	// and every reader derive their paths from listing the same modpack mods/ folder, with no
	// symlink indirection between them, so lexical equality already holds.
	private static Path canonical(Path jar) {
		return jar.toAbsolutePath().normalize();
	}

	// Per-jar facts derived from a single jar mount, cached for the JVM's life.
	private record JarInfo(boolean activelyRunInPlace, Set<String> services, Map<String, List<String>> serviceImpls, boolean standalone) {}

	private static final Map<Path, JarInfo> JAR_INFO = new ConcurrentHashMap<>();

	private static JarInfo info(Path jar) {
		return JAR_INFO.computeIfAbsent(canonical(jar), EarlyServiceLayer::inspect);
	}

	private static JarInfo inspect(Path jar) {
		boolean activelyRun = false;
		Set<String> services = Set.of();
		Map<String, List<String>> impls = new HashMap<>();
		boolean standalone = false;
		try (FileSystem fs = FileSystems.newFileSystem(jar)) {
			// Scoped to what this loader version actually handles (knownServices()), so a legacy/
			// removed SPI doesn't wrongly make an otherwise in-place-able mod look unhandleable.
			services = FileInspection.getServices(fs, knownServices());
			standalone = Files.exists(fs.getPath("META-INF/neoforge.mods.toml"));
			for (String service : ACTIVELY_RUN_SERVICES) {
				if (Files.exists(fs.getPath(service))) {
					activelyRun = true;
					impls.put(service, readServiceImpls(fs, service));
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[AutoModpack] Could not inspect {}; not handling it in place", jar.getFileName(), e);
		}
		return new JarInfo(activelyRun, services, impls, standalone);
	}

	/** The impl class names of an {@link #ACTIVELY_RUN_SERVICES} service declared at the jar's root. */
	public static List<String> serviceImpls(Path jar, String serviceFile) {
		return info(jar).serviceImpls().getOrDefault(serviceFile, List.of());
	}

	public static void runCandidateLocators(List<Path> jars, ILaunchContext context, IDiscoveryPipeline pipeline) {
		List<IModFileCandidateLocator> locators = new ArrayList<>();
		for (Path jar : jars) {
			ClassLoader cl = classLoaderFor(jar);
			if (cl == null) continue;
			for (String impl : serviceImpls(jar, CANDIDATE_LOCATOR_SERVICE)) {
				try {
					locators.add((IModFileCandidateLocator) Class.forName(impl, true, cl).getDeclaredConstructor().newInstance());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to load candidate locator {} from {}", impl, jar.getFileName(), t);
				}
			}
		}
		// Run highest-priority first (IOrderedProvider order) across ALL early-service jars, so a
		// replayed locator's declared priority is honoured relative to the others; raw modsToLoad
		// (filesystem) order would silently drop it.
		locators.sort(Comparator.comparingInt(IModFileCandidateLocator::getPriority).reversed());
		for (IModFileCandidateLocator locator : locators) {
			try {
				LOGGER.debug("[AutoModpack] Running in-place candidate locator {} (priority {})", locator.getClass().getName(), locator.getPriority());
				locator.findCandidates(context, pipeline);
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to run candidate locator {}", locator.getClass().getName(), t);
			}
		}
	}

	public static void runDependencyLocators(List<Path> jars, List<?> loadedMods, IDiscoveryPipeline pipeline) {
		@SuppressWarnings("unchecked")
		List<IModFile> mods = (List<IModFile>) loadedMods;
		List<IDependencyLocator> locators = new ArrayList<>();
		for (Path jar : jars) {
			ClassLoader cl = classLoaderFor(jar);
			if (cl == null) continue;
			for (String impl : serviceImpls(jar, DEPENDENCY_LOCATOR_SERVICE)) {
				try {
					locators.add((IDependencyLocator) Class.forName(impl, true, cl).getDeclaredConstructor().newInstance());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to load dependency locator {} from {}", impl, jar.getFileName(), t);
				}
			}
		}
		locators.sort(Comparator.comparingInt(IDependencyLocator::getPriority).reversed());
		for (IDependencyLocator locator : locators) {
			try {
				LOGGER.debug("[AutoModpack] Running in-place dependency locator {} (priority {})", locator.getClass().getName(), locator.getPriority());
				locator.scanMods(mods, pipeline);
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to run dependency locator {}", locator.getClass().getName(), t);
			}
		}
	}

	/**
	 * Forwards an early-service jar's {@code IModFileReader}s into the live discovery pipeline via
	 * reflection - there is no public API to register one.
	 */
	public static void runModFileReaders(Path jar, IDiscoveryPipeline pipeline) {
		ClassLoader cl = classLoaderFor(jar);
		if (cl == null) return;
		List<Object> readers = new ArrayList<>();
		for (String impl : serviceImpls(jar, MOD_FILE_READER_SERVICE)) {
			try {
				readers.add(Class.forName(impl, true, cl).getDeclaredConstructor().newInstance());
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to instantiate IModFileReader {} from {}", impl, jar.getFileName(), t);
			}
		}
		if (readers.isEmpty()) return;

		try {
			Field outer = pipeline.getClass().getDeclaredField("this$0");
			outer.setAccessible(true);
			Object modDiscoverer = outer.get(pipeline);
			Field readersField = modDiscoverer.getClass().getDeclaredField("modFileReaders");
			readersField.setAccessible(true);
			@SuppressWarnings("unchecked")
			List<Object> current = (List<Object>) readersField.get(modDiscoverer);
			List<Object> merged = new ArrayList<>(current);
			merged.addAll(readers);
			merged.sort(Comparator.comparingInt(EarlyServiceLayer::providerPriority).reversed());
			readersField.set(modDiscoverer, List.copyOf(merged));
			LOGGER.debug("[AutoModpack] Forwarded {} in-place IModFileReader(s) from {} into mod discovery", readers.size(), jar.getFileName());
		} catch (Throwable t) {
			LOGGER.error("[AutoModpack] Could not forward IModFileReader(s) from {} into mod discovery; a mod relying on that reader may need copy-to-standard",
					jar.getFileName(), t);
		}
	}

	private static int providerPriority(Object provider) {
		try {
			return (int) provider.getClass().getMethod("getPriority").invoke(provider);
		} catch (Throwable t) {
			return 0;
		}
	}

	public static boolean eligibleForInPlace(Path jar) {
		JarInfo info = info(jar);
		return info.activelyRunInPlace() && HANDLEABLE_SERVICES.containsAll(info.services());
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
