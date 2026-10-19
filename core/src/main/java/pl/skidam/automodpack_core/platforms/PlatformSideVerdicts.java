package pl.skidam.automodpack_core.platforms;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The stage that asks Modrinth whether clients run the leftover universal mod jars, fills the platform cache with what
 * it learns and answers with the reasons to keep them off clients. It owns the failure policy: a round that could not
 * be asked is logged with its size and leaves the jar in the pack, never reported as "the platform matched nothing".
 */
public final class PlatformSideVerdicts {
	private final Path cacheDirectory;

	public PlatformSideVerdicts(Path cacheDirectory) {
		this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath().normalize();
	}

	/** Exclusion reasons for the given mod jars, keyed by the same normalized sha1s; an absent key stays in the pack. */
	public Map<String, String> excludeFromClientPack(Set<String> modJars) {
		if (modJars.isEmpty()) return Map.of();
		try (PlatformCache cache = PlatformCache.open(cacheDirectory)) {
			askModrinth(cache, modJars);
			Map<String, PlatformCache.Record> records = cache.getAll(modJars);
			Map<String, String> reasons = new HashMap<>();
			for (String sha1 : modJars) {
				PlatformCache.Record record = records.get(sha1);
				String reason = record == null ? null : record.clientExclusionReason();
				if (reason != null) reasons.put(sha1, reason);
			}
			return reasons;
		} catch (IOException | UncheckedIOException e) {
			LOGGER.error("The platform cache at {} could not be read, so no mod jar among {} carries a client-side verdict", cacheDirectory, modJars.size(), e);
			return Map.of();
		}
	}

	private static void askModrinth(PlatformCache cache, Set<String> modJars) {
		List<String> missing = new ArrayList<>();
		Map<String, PlatformCache.Record> records = cache.getAll(modJars);
		for (String sha1 : modJars) if (!askedModrinth(records.get(sha1))) missing.add(sha1);
		if (missing.isEmpty()) return;
		List<ModrinthAPI> hits = ModrinthAPI.getModsInfosFromListOfSHA1(missing);
		if (hits == null) {
			LOGGER.warn("Modrinth could not be asked about {} mod hashes; those jars stay in the pack until a later scan answers", missing.size());
			return;
		}
		for (ModrinthAPI hit : hits) if (hit != null && HashUtils.isSha1(hit.SHA1Hash())) cache.putModrinth(hit.SHA1Hash(), hit, null);
	}

	/** A Modrinth answer is on the record once it carries an environment field; a blank one still answers, an absent field means the entry predates it. */
	private static boolean askedModrinth(PlatformCache.Record record) {
		return record != null && record.modrinth() != null && record.modrinth().environment() != null;
	}
}
