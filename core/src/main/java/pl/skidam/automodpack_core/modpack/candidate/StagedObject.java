package pl.skidam.automodpack_core.modpack.candidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import pl.skidam.automodpack_core.utils.HashUtils;

/** A verified temporary snapshot that is owned by a {@link ModpackCandidate} until promotion. */
public record StagedObject(String sha1, long size, Path stagedPath) {
	public StagedObject {
		if (!HashUtils.isSha1(sha1)) throw new IllegalArgumentException("Invalid staged object SHA-1");
		if (size < 0) throw new IllegalArgumentException("Staged object size cannot be negative");
		sha1 = HashUtils.normalizeSha1(sha1);
		stagedPath = Objects.requireNonNull(stagedPath).toAbsolutePath().normalize();
	}

	public void delete() throws IOException {
		Files.deleteIfExists(stagedPath);
	}
}
