package pl.skidam.automodpack_core.client;

/** Progress of the Modrinth/CurseForge source lookup behind a review: how many jars have first-party hits. */
public record SourceAvailability(int totalFiles, int resolvedFiles, boolean complete, boolean cancelled) {}
