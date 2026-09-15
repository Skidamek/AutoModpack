package pl.skidam.automodpack_core.protocol;

import java.nio.file.Path;

/** Outcome of one document fetch: the written file, or {@code unchanged} when the server answered a conditional request with UNCHANGED (nothing was written). */
public record DocumentFetch(Path path, boolean unchanged) {}
