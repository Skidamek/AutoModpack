package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;

/** Persistent immutable client copies of complete server generation records. */
public final class ClientGenerationStore {
	private final ClientStorage storage;

	public ClientGenerationStore(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage);
	}

	public void write(GenerationRecord record) throws IOException {
		Objects.requireNonNull(record, "record");
		storage.ensureRoots();
		Path path = storage.generationManifest(record.metadata().generationId());
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			GenerationRecord existing = read(path).orElseThrow(() -> new IOException("Stored client generation is invalid: " + path));
			if (!existing.equals(record)) throw new IOException("Client generation record already exists with different content: " + path);
			return;
		}
		Files.createDirectories(path.getParent());
		ConfigTools.writeAtomic(path, record.toFields());
		GenerationRecord written = read(path).orElseThrow(() -> new IOException("Stored client generation could not be verified: " + path));
		if (!written.equals(record)) throw new IOException("Stored client generation verification failed: " + path);
	}

	public Optional<GenerationRecord> read(String generationId) throws IOException {
		return read(storage.generationManifest(generationId));
	}

	public List<String> generationIds() throws IOException {
		if (!Files.exists(storage.recordsDirectory(), LinkOption.NOFOLLOW_LINKS)) return List.of();
		try (Stream<Path> paths = Files.list(storage.recordsDirectory())) {
			return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	/** Returns the committed lineage ending at the generation selected by active-state.json. */
	public List<GenerationRecord> lineage(String modpackId, String generationId) throws IOException {
		ModpackId.requireValid(modpackId);
		List<GenerationRecord> reverse = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		String current = generationId;
		while (current != null && !current.isEmpty()) {
			if (!visited.add(current)) throw new IOException("Client generation lineage contains a cycle");
			String generation = current;
			GenerationRecord record = read(generation).orElseThrow(() -> new IOException("Client generation lineage is incomplete: " + generation));
			if (!modpackId.equals(record.manifest().modpackId())) throw new IOException("Client generation lineage crosses modpack IDs");
			reverse.add(record);
			current = record.metadata().parentGenerationId();
		}
		java.util.Collections.reverse(reverse);
		return List.copyOf(reverse);
	}

	private static Optional<GenerationRecord> read(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generation manifest is not a regular file: " + path);
		try {
			return ConfigTools.read(path, pl.skidam.automodpack_core.config.Jsons.CompleteModpackContentFields.class).map(GenerationRecord::fromFields);
		} catch (RuntimeException e) {
			throw new IOException("Client generation manifest is invalid: " + path, e);
		}
	}
}
