package pl.skidam.automodpack_core.modpack.generation;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable object paths published for the active generation host. */
public final class GenerationHosting {
	private final NavigableMap<String, Path> paths;

	public GenerationHosting(Map<String, Path> paths) {
		TreeMap<String, Path> normalized = new TreeMap<>();
		if (paths != null) {
			for (var entry : paths.entrySet()) {
				String key = Objects.requireNonNull(entry.getKey(), "hosting path key");
				Path path = Objects.requireNonNull(entry.getValue(), "hosting path").toAbsolutePath().normalize();
				normalized.put(key, path);
			}
		}
		this.paths = Collections.unmodifiableNavigableMap(normalized);
	}

	public NavigableMap<String, Path> asMap() {
		return paths;
	}
}
