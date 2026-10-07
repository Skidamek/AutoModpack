package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hands paths to Sinytra Connector's {@code connector.additionalModLocations} system property - the
 * one hook that makes plain Fabric jars loadable on (Neo)Forge. Connector reads the property during
 * its own discovery, so every writer merges into whatever is already set and never overwrites or
 * clears it. Which paths each family offers is an eligibility decision at the call site, not here.
 */
public final class ConnectorFallback {
	public static final String PROPERTY = "connector.additionalModLocations";
	public static final String PLACEHOLDER_PROPERTY = "connector:placeholder";

	private ConnectorFallback() {}

	public static boolean isPlaceholder(Map<String, ?> fileProperties) {
		return fileProperties != null && fileProperties.containsKey(PLACEHOLDER_PROPERTY);
	}

	/**
	 * Adds {@code paths} ahead of whatever is already configured; a no-op when both are empty. Plain absolute paths, because Connector passes each entry straight to {@code Paths.get} and a URI form arrives as one
	 * literal path that does not exist.
	 */
	public static void offer(List<Path> paths) {
		String configured = paths.stream().map(Path::toString).collect(Collectors.joining(","));
		String existing = System.getProperty(PROPERTY, "");
		String merged = configured.isEmpty() ? existing : existing.isEmpty() ? configured : configured + "," + existing;
		if (!merged.isEmpty()) System.setProperty(PROPERTY, merged);
	}
}
