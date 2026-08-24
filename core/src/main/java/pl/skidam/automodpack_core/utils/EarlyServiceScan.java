package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/**
 * Selects the modpack-folder jars an early-service bootstrapper should host in place: every jar the
 * loader-specific eligibility test accepts, minus byte-identical duplicates already in the standard
 * {@code mods/} directory (the loader already handles that copy natively).
 */
public final class EarlyServiceScan {

	private EarlyServiceScan() {}

	/**
	 * Eligibility is checked before hashing, since it's cheap and most modpack jars never need a hash.
	 * The standard-mods hash set is computed lazily, only once an eligible jar needs the comparison.
	 */
	public static List<Path> eligibleJars(Path modpackMods, Path standardMods, Predicate<Path> eligibleForInPlace, FileMetadataCache cache) throws IOException {
		List<Path> earlyServiceJars = new ArrayList<>();
		Set<String> standardModHashes = null;
		try (Stream<Path> stream = Files.list(modpackMods)) {
			for (Path jar : stream.filter(EarlyServiceScan::isJar).toList()) {
				if (!eligibleForInPlace.test(jar)) continue;
				if (standardModHashes == null) standardModHashes = jarHashes(standardMods, cache);
				String hash = FileIntegrity.identityHash(jar, cache);
				if (hash != null && standardModHashes.contains(hash)) continue;
				earlyServiceJars.add(jar);
			}
		}
		return earlyServiceJars;
	}

	private static Set<String> jarHashes(Path dir, FileMetadataCache cache) {
		Set<String> hashes = new HashSet<>();
		if (dir == null || !Files.isDirectory(dir)) return hashes;
		try (Stream<Path> stream = Files.list(dir)) {
			for (Path jar : stream.filter(EarlyServiceScan::isJar).toList()) {
				String hash = FileIntegrity.identityHash(jar, cache);
				if (hash != null) hashes.add(hash);
			}
		} catch (Exception e) {
			return hashes;
		}
		return hashes;
	}

	private static boolean isJar(Path path) {
		return JarUtils.isRegularJar(path);
	}
}
