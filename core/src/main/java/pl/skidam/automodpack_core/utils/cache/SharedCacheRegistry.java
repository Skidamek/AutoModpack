package pl.skidam.automodpack_core.utils.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Owns path-normalized, reference-counted cache instances within one process. */
final class SharedCacheRegistry<T> {
	private final Map<Path, Entry<T>> entries = new HashMap<>();

	public synchronized T acquire(Path path, Function<Path, T> factory) throws IOException {
		Path normalized = path.toAbsolutePath().normalize();
		Files.createDirectories(normalized);
		Entry<T> existing = entries.get(normalized);
		if (existing != null) {
			existing.references++;
			return existing.instance;
		}
		T instance = factory.apply(normalized);
		entries.put(normalized, new Entry<>(instance));
		return instance;
	}

	public synchronized boolean release(Path path, T instance) {
		Path normalized = path.toAbsolutePath().normalize();
		Entry<T> entry = entries.get(normalized);
		if (entry == null || entry.instance != instance || --entry.references > 0) return false;
		entries.remove(normalized);
		return true;
	}

	private static final class Entry<T> {
		private final T instance;
		private int references = 1;

		private Entry(T instance) {
			this.instance = instance;
		}
	}
}
