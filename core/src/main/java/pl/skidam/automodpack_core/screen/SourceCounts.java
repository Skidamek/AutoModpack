package pl.skidam.automodpack_core.screen;

/**
 * Jar counts of one reviewed target by where their hashes were found. The Modrinth and CurseForge
 * counts overlap when a jar is on both platforms; server-only jars are exactly the unverified set.
 */
public record SourceCounts(int modrinth, int curseforge, int serverOnly) {}
