package pl.skidam.automodpack_core.loader;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import pl.skidam.automodpack_core.utils.FileInspection;

/**
 * Per-jar early-service facts, derived once per jar from a single zip mount and cached for the JVM's
 * life (jar content is immutable for the run) - shared by every loader generation's {@code
 * EarlyServiceLayer}, which passes in its own known and replayed service sets. Without this each
 * layer re-derived the same booleans and impl lists from its own mount, ~10 mounts per early-service
 * jar per boot. The service scan is scoped to the services the running loader version actually
 * handles, so a legacy or removed SPI that loader version never runs cannot make an otherwise
 * hostable jar look unhostable.
 */
public final class ServiceJarIndex {
	private ServiceJarIndex() {}

	/**
	 * What one jar mount answered: the known services the jar declares, the impl class names of the
	 * services the calling generation replays, and the caller's own extra facts. A jar that could not
	 * be mounted answers empty with a {@code null} extra - callers treat that as their falsy default.
	 */
	public record Facts<T>(Set<String> services, Map<String, List<String>> serviceImpls, T extra) {

		/** The impl class names of a replayed service, or empty when the jar does not declare it. */
		public List<String> implsOf(String serviceFile) {
			return serviceImpls.getOrDefault(serviceFile, List.of());
		}
	}

	/** Reads generation-specific facts (a standalone-mod probe, say) from the same open mount. */
	@FunctionalInterface
	public interface ExtraFacts<T> {
		T read(FileSystem jar) throws IOException;
	}

	private static final Map<Path, Facts<?>> FACTS = new ConcurrentHashMap<>();

	public static Facts<Void> facts(Path jar, Set<String> knownServices, List<String> replayedServices) {
		return facts(jar, knownServices, replayedServices, fs -> null);
	}

	@SuppressWarnings("unchecked")
	public static <T> Facts<T> facts(Path jar, Set<String> knownServices, List<String> replayedServices, ExtraFacts<T> extraFacts) {
		// One generation runs per JVM, so every caller passes the same sets and the shared cache stays coherent.
		return (Facts<T>) FACTS.computeIfAbsent(canonical(jar), path -> inspect(path, knownServices, replayedServices, extraFacts));
	}

	public static Path canonical(Path jar) {
		return jar.toAbsolutePath().normalize();
	}

	private static <T> Facts<T> inspect(Path jar, Set<String> knownServices, List<String> replayedServices, ExtraFacts<T> extraFacts) {
		Set<String> services = Set.of();
		Map<String, List<String>> impls = new HashMap<>();
		T extra = null;
		try (FileSystem fs = FileSystems.newFileSystem(jar)) {
			services = FileInspection.getServices(fs, knownServices);
			for (String service : replayedServices) {
				if (Files.exists(fs.getPath(service))) {
					impls.put(service, LoaderServiceFiles.readImplementations(fs, service));
				}
			}
			extra = extraFacts.read(fs);
		} catch (Exception e) {
			LOGGER.warn("[AutoModpack] Could not inspect {}; not handling it in place", jar.getFileName(), e);
		}
		return new Facts<>(services, Map.copyOf(impls), extra);
	}
}
