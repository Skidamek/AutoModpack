package pl.skidam.automodpack_core.platforms;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Platform verdict for a batch of hosted objects: the file size each platform reports for the objects it serves itself.
 * Objects absent from the result have no trustworthy platform source and stay in an export.
 */
public interface PlatformSourceLookup {

	Map<String, Long> platformSizes(Collection<Query> queries);

	/** sha1 is normalized lowercase hex, size is the hosted object's byte size, and objectPath points at its bytes in the store. */
	record Query(String sha1, long size, Path objectPath) {
		public Query {
			Objects.requireNonNull(sha1, "sha1");
			if (size < 0) throw new IllegalArgumentException("Negative object size");
			Objects.requireNonNull(objectPath, "objectPath");
		}
	}

	/** Everything unresolvable; keeps exports offline and deterministic. */
	static PlatformSourceLookup none() {
		return queries -> Map.of();
	}

	/** Resolves through the Modrinth and CurseForge APIs, one best-effort round per platform. */
	static PlatformSourceLookup resolving() {
		return new ApiPlatformSourceLookup();
	}
}
