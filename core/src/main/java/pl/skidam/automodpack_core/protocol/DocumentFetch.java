package pl.skidam.automodpack_core.protocol;

import java.nio.file.Path;

/**
 * Outcome of one document fetch: the written file, or {@code unchanged} when the server answered a conditional request
 * with UNCHANGED (nothing was written). The {@code etag} is the host's own validator header from a 200 answer, present
 * when it sent one: replayed next fetch as a second If-None-Match validator, it lets foreign hosts (buckets, static
 * CDNs) whose ETags are not our sha1 answer 304 for unchanged documents.
 */
public record DocumentFetch(Path path, boolean unchanged, String etag) {}
