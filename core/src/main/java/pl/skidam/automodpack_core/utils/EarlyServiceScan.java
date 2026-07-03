package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Selects the modpack-folder jars an early-service bootstrapper should host in place: every jar the
 * loader-specific eligibility test accepts, minus byte-identical duplicates of jars already in the
 * standard {@code mods/} directory (the loader already handles that copy natively). One shared
 * implementation for the three bootstrap entry points (NeoForge fml4, fml10/fml11, Forge).
 */
public final class EarlyServiceScan {

    private EarlyServiceScan() {}

    /**
     * Checking eligibility first (a handful of root-level {@code Files.exists} checks on an
     * already-open FileSystem) before hashing (a full-content SHA-1 read) avoids paying for a hash
     * on every ordinary, non-early-service mod - most modpack jars never need one. The
     * standard-mods hash set is itself a full hash of every standard-mods/ jar, so it is computed
     * lazily too, only once an eligible jar actually needs the comparison.
     */
    public static List<Path> eligibleJars(Path modpackMods, Predicate<Path> eligibleForInPlace) throws IOException {
        List<Path> earlyServiceJars = new ArrayList<>();
        Set<String> standardModHashes = null;
        try (Stream<Path> stream = Files.list(modpackMods)) {
            for (Path jar : stream.filter(EarlyServiceScan::isJar).toList()) {
                if (!eligibleForInPlace.test(jar)) {
                    continue;
                }
                if (standardModHashes == null) {
                    standardModHashes = HashUtils.getJarHashes(Constants.MODS_DIR);
                }
                String hash = HashUtils.getHash(jar);
                if (hash != null && standardModHashes.contains(hash)) {
                    continue;
                }
                earlyServiceJars.add(jar);
            }
        }
        return earlyServiceJars;
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".jar");
    }
}
