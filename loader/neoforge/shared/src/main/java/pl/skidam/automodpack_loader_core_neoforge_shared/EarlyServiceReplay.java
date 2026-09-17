package pl.skidam.automodpack_loader_core_neoforge_shared;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

/**
 * Replays the mod-locating services of hosted NeoForge early-service jars into AutoModpack's own
 * discovery - shared verbatim by both neoforge generations, ModLauncher-era fml4 and the
 * flat-classloader fml10/11. FML enumerated locators and readers from its classloader chain BEFORE
 * the candidate phase that hosted these jars, so its own passes never see them: their candidate
 * locators, dependency locators and mod file readers must be driven by hand, which is what the
 * methods below do - priority-ordered across all hosted jars, each invocation isolated so one
 * misbehaving replayed service cannot abort discovery. Where a jar's implementations and the
 * classloader that can see them live is per-generation knowledge, passed in by the caller; only the
 * replay machinery itself lives here.
 *
 * <p>
 * Compiled against the oldest consumer pin (neoforgespi 4.x): every type in these signatures exists
 * with identical erasure on fml10/11, so both generations link this class safely.
 */
public final class EarlyServiceReplay {

	private EarlyServiceReplay() {}

	/**
	 * Runs the {@code IModFileCandidateLocator}s declared inside the hosted jars against AutoModpack's
	 * discovery pipeline - how mods like Sodium (fml4) or a Connector-style locator (fml10/11) load
	 * their real (inner) mod jar in place.
	 */
	public static void runCandidateLocators(List<Path> jars, Function<Path, List<String>> implsFor, Function<Path, ClassLoader> loaderFor, ILaunchContext context,
			IDiscoveryPipeline pipeline) {
		List<IModFileCandidateLocator> locators = new ArrayList<>();
		for (Path jar : jars) {
			ClassLoader cl = loaderFor.apply(jar);
			if (cl == null) continue;
			for (String impl : implsFor.apply(jar)) {
				try {
					locators.add((IModFileCandidateLocator) Class.forName(impl, true, cl).getDeclaredConstructor().newInstance());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to load candidate locator {} from {}", impl, jar.getFileName(), t);
				}
			}
		}
		// Highest priority first (IOrderedProvider order) across ALL hosted jars, so a replayed
		// locator's declared priority is honoured relative to the others; raw staging (filesystem)
		// order would silently drop it.
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
	 * Runs the {@code IDependencyLocator}s declared inside the hosted jars - how a jar-in-jar
	 * dependency locator (CrashAssistant-style on fml4, Ixeris-style on fml10/11) contributes its
	 * real (inner) mod jar in place.
	 */
	public static void runDependencyLocators(List<Path> jars, Function<Path, List<String>> implsFor, Function<Path, ClassLoader> loaderFor, List<IModFile> loadedMods,
			IDiscoveryPipeline pipeline) {
		List<IDependencyLocator> locators = new ArrayList<>();
		for (Path jar : jars) {
			ClassLoader cl = loaderFor.apply(jar);
			if (cl == null) continue;
			for (String impl : implsFor.apply(jar)) {
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
				locator.scanMods(loadedMods, pipeline);
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to run dependency locator {}", locator.getClass().getName(), t);
			}
		}
	}

	/**
	 * Splices instantiated {@code IModFileReader}s into the live discovery pipeline, so a modpack mod
	 * that ships a reader for a custom mod-file format can interpret candidates in place. Unlike a
	 * locator, a reader isn't invoked directly: the pipeline consults its own {@code modFileReaders}
	 * list (built by {@code ModDiscoverer} from the SERVICE/PLUGIN layers, which a modpack jar never
	 * reaches) whenever it reads a candidate. There is no public API to add one, so this reaches the
	 * discoverer's list by reflection - the loader is an automatic module, so plain {@code
	 * setAccessible} reaches it - and re-sorts by {@code IOrderedProvider} priority to keep the
	 * loader's precedence contract.
	 *
	 * @param source
	 *            display name of where the readers came from, for the logs
	 */
	public static void forwardModFileReaders(List<Object> readers, String source, IDiscoveryPipeline pipeline) {
		if (readers.isEmpty()) return;

		try {
			// pipeline is ModDiscoverer's DiscoveryPipeline; reach its outer ModDiscoverer's reader list.
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
			merged.sort(Comparator.comparingInt(EarlyServiceReplay::providerPriority).reversed());
			readersField.set(modDiscoverer, List.copyOf(merged));
			LOGGER.debug("[AutoModpack] Forwarded {} in-place IModFileReader(s) from {} into mod discovery", readers.size(), source);
		} catch (Throwable t) {
			LOGGER.error("[AutoModpack] Could not forward IModFileReader(s) from {} into mod discovery; a mod relying on that reader may need copy-to-standard", source, t);
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
}
