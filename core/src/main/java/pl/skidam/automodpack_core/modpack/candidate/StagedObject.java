package pl.skidam.automodpack_core.modpack.candidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** A verified temporary snapshot that is owned by a {@link ModpackCandidate} until promotion. */
public record StagedObject(String sha1, long size, Path stagedPath) {
	public StagedObject {
		if (sha1 == null || !sha1.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("Invalid staged object SHA-1");
		if (size < 0) throw new IllegalArgumentException("Staged object size cannot be negative");
		sha1 = sha1.toLowerCase(Locale.ROOT);
		stagedPath = Objects.requireNonNull(stagedPath).toAbsolutePath().normalize();
	}

	public void delete() throws IOException {
		Files.deleteIfExists(stagedPath);
	}
}
