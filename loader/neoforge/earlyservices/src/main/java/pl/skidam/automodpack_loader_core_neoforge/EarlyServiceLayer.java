package pl.skidam.automodpack_loader_core_neoforge;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.lang.reflect.Field;
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

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import pl.skidam.automodpack_core.loader.LoaderServiceFiles;
import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.utils.FileInspection;

/**
 * NeoForge 21.6+ ("FML 10.x"/"11.x") drops ModLauncher/securejarhandler entirely: early-service
 * jars are chained onto ONE flat {@code URLClassLoader} lineage that {@code FMLLoader} owns and
 * grows via its private {@code appendLoader(name, List<JarContents>)}. Because
 * {@code FMLLoader.buildTransformingLoader()} always does
 * {@code gameLoader.setFallbackClassLoader(currentClassLoader)}, growing that same chain with a
 * active-projection jar's classes BEFORE the game loader is built makes them resolve through that
 * native fallback with no manual class/resource bridging, {@code addReads}, or {@code Unsafe}.
 *
 * <p>
 * This class inspects and registers active-projection early-service jars. Once appended, FML still
 * discovers their language loaders and class processors through its native (post-discovery)
 * ServiceLoader passes, but its locator/reader enumerations ran BEFORE the candidate phase that does
 * the appending - so AutoModpack replays candidate locators, dependency locators and mod file readers
 * itself (below), and invokes GraphicsBootstrapper directly because FML's own bootstrapper pass
 * already happened by the time we host.
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

	// Services AutoModpack itself must drive for the hosted jars. FML enumerates locator and reader
	// implementations from its classloader chain BEFORE the discovery phases run, so jars appended
	// during the candidate phase are invisible to those enumerations - the replays below stand in
	// for them. GraphicsBootstrapper stays special: FML's own bootstrapper pass already happened by
	// the time we host, so EarlyModLocator invokes it directly.
	private static final List<String> REPLAYED_SERVICES = List.of(GRAPHICS_BOOTSTRAPPER_SERVICE, CANDIDATE_LOCATOR_SERVICE, DEPENDENCY_LOCATOR_SERVICE,
			MOD_FILE_READER_SERVICE);

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

	// Modpack-folder jars EarlyModLocator has appended to FMLLoader's classloader chain, the flat
	// chain tail that can see them, and the registration order (locators replay in that order).
	private static final Set<Path> REGISTERED_JARS = ConcurrentHashMap.newKeySet();
	private static volatile List<Path> registeredJars = List.of();
	private static volatile ClassLoader hostedLoader;

	static void register(List<Path> jars, ClassLoader loader) {
		for (Path jar : jars) {
			REGISTERED_JARS.add(canonical(jar));
		}
		registeredJars = jars.stream().map(EarlyServiceLayer::canonical).toList();
		hostedLoader = loader;
	}

	public static boolean isEarlyServiceJar(Path jar) {
		return jar != null && REGISTERED_JARS.contains(canonical(jar));
	}

	/** The hosted jars in registration order - the replay runs their locators in staging order. */
	static List<Path> registeredJars() {
		return registeredJars;
	}

	/** The flat chain tail {@code EarlyModLocator} got back from {@code FMLLoader.getCurrentClassLoader()} after the append; the only loader that sees the hosted jars. */
	private static ClassLoader hostedClassLoader() {
		ClassLoader loader = hostedLoader;
		if (loader == null) throw new IllegalStateException("AutoModpack replays locators before any early-service jar was appended to FMLLoader's classloader chain");
		return loader;
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
			for (String service : REPLAYED_SERVICES) {
				if (Files.exists(fs.getPath(service))) {
					impls.put(service, LoaderServiceFiles.readImplementations(fs, service));
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[AutoModpack] Could not inspect {}; not handling it in place", jar.getFileName(), e);
		}
		return new JarInfo(eligible, impls, standalone);
	}

	/** The implementation class names of a {@link #REPLAYED_SERVICES} service declared at the jar's root. */
	public static List<String> serviceImpls(Path jar, String serviceFile) {
		return info(jar).serviceImpls().getOrDefault(serviceFile, List.of());
	}

	/**
	 * Runs the {@code IModFileCandidateLocator}s declared inside the hosted jars against AutoModpack's
	 * discovery pipeline - FML enumerated candidate locators before the candidate phase that appended
	 * these jars, so this is how mods like a Connector-style locator get to run in place at all.
	 */
	public static void runCandidateLocators(ILaunchContext context, IDiscoveryPipeline pipeline) {
		List<IModFileCandidateLocator> locators = new ArrayList<>();
		for (Path jar : registeredJars()) {
			for (String impl : serviceImpls(jar, CANDIDATE_LOCATOR_SERVICE)) {
				try {
					locators.add((IModFileCandidateLocator) Class.forName(impl, true, hostedClassLoader()).getDeclaredConstructor().newInstance());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to load candidate locator {} from {}", impl, jar.getFileName(), t);
				}
			}
		}
		// Highest priority first, so a replayed locator's declared priority is honoured relative to
		// the others; registration (staging) order would silently drop it.
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

	/**
	 * Runs the {@code IDependencyLocator}s declared inside the hosted jars - FML enumerated dependency
	 * locators before the candidate phase that appended these jars, so this is how a jar-in-jar
	 * dependency locator (CrashAssistant-style) contributes its real inner mod in place.
	 */
	public static void runDependencyLocators(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
		List<IDependencyLocator> locators = new ArrayList<>();
		for (Path jar : registeredJars()) {
			for (String impl : serviceImpls(jar, DEPENDENCY_LOCATOR_SERVICE)) {
				try {
					locators.add((IDependencyLocator) Class.forName(impl, true, hostedClassLoader()).getDeclaredConstructor().newInstance());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to load dependency locator {} from {}", impl, jar.getFileName(), t);
				}
			}
		}
		locators.sort(Comparator.comparingInt(IDependencyLocator::getPriority).reversed());
		for (IDependencyLocator locator : locators) {
			try {
				LOGGER.debug("[AutoModpack] Running in-place dependency locator {} (priority {})", locator.getClass().getName(), locator.getPriority());
				locator.scanMods(loadedMods, pipeline);
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to run dependency locator {}", locator.getClass().getName(), t);
			}
		}
	}

	/**
	 * Forwards a hosted jar's {@code IModFileReader}s into the live discovery pipeline, so a modpack
	 * mod that ships a reader for a custom mod-file format can interpret candidates in place - FML
	 * enumerated readers before the candidate phase that appended the jar, so no replay call would
	 * ever reach them. Unlike a locator, a reader isn't invoked directly: the pipeline consults the
	 * discoverer's {@code modFileReaders} list whenever it reads a candidate, so this splices ours
	 * into that list by reflection and re-sorts by {@code IOrderedProvider} priority to keep the
	 * loader's precedence contract.
	 */
	public static void runModFileReaders(IDiscoveryPipeline pipeline) {
		List<Object> readers = new ArrayList<>();
		for (Path jar : registeredJars()) {
			for (String impl : serviceImpls(jar, MOD_FILE_READER_SERVICE)) {
				try {
					readers.add(Class.forName(impl, true, hostedClassLoader()).getDeclaredConstructor().newInstance());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to instantiate IModFileReader {} from {}", impl, jar.getFileName(), t);
				}
			}
		}
		if (readers.isEmpty()) return;

		try {
			// pipeline is ModDiscoverer's anonymous DiscoveryPipeline; reach its outer ModDiscoverer's reader list.
			Field outer = pipeline.getClass().getDeclaredField("this$0");
			outer.setAccessible(true);
			Object modDiscoverer = outer.get(pipeline);
			Field readersField = modDiscoverer.getClass().getDeclaredField("modFileReaders");
			readersField.setAccessible(true);
			@SuppressWarnings("unchecked")
			List<Object> current = (List<Object>) readersField.get(modDiscoverer);
			List<Object> merged = new ArrayList<>(current);
			merged.addAll(readers);
			// ModDiscoverer sorts readers by IOrderedProvider.getPriority(), highest first.
			merged.sort(Comparator.comparingInt(EarlyServiceLayer::providerPriority).reversed());
			readersField.set(modDiscoverer, List.copyOf(merged));
			LOGGER.debug("[AutoModpack] Forwarded {} in-place IModFileReader(s) from {} into mod discovery", readers.size(),
					registeredJars().stream().map(Path::getFileName).toList());
		} catch (Throwable t) {
			LOGGER.error("[AutoModpack] Could not forward IModFileReader(s) from {} into mod discovery; a mod relying on that reader may need copy-to-standard",
					registeredJars().stream().map(Path::getFileName).toList(), t);
		}
	}

	/** {@code IOrderedProvider.getPriority()} of a reader, or the default (0) if it can't be read. */
	private static int providerPriority(Object provider) {
		try {
			return (int) provider.getClass().getMethod("getPriority").invoke(provider);
		} catch (Throwable t) {
			return 0;
		}
	}

	public static boolean eligibleForInPlace(Path jar) {
		return info(jar).eligible();
	}

	public static boolean isStandaloneModFile(Path jar) {
		return info(jar).standalone();
	}

}
