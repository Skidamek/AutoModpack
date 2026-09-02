package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;

/** Persistent immutable client copies of complete server generation records. */
public final class ClientGenerationStore {
	private final ClientStorage storage;

	public ClientGenerationStore(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage);
	}

	public void write(GenerationRecord record) throws IOException {
		write(record, GenerationPatchNoteHistory.forRecord(record));
	}

	public void write(GenerationRecord record, List<GenerationPatchNoteHistory.Entry> patchNotesHistory) throws IOException {
		Objects.requireNonNull(record, "record");
		Objects.requireNonNull(patchNotesHistory, "patchNotesHistory");
		storage.ensureRoots();
		Path path = storage.generationManifest(record.metadata().generationId());
		Jsons.CompleteModpackContentFields fields = record.toFields();
		GenerationPatchNoteHistory.writeFields(fields, patchNotesHistory);
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			Jsons.CompleteModpackContentFields existingFields = readFields(path).orElseThrow(() -> new IOException("Stored client generation is invalid: " + path));
			GenerationRecord existing = GenerationRecord.fromFields(existingFields);
			if (!existing.equals(record)) throw new IOException("Client generation record already exists with different content: " + path);
			if (!GenerationPatchNoteHistory.fromFields(existingFields).equals(patchNotesHistory)) {
				if (existingFields.patchNotesHistory != null && !existingFields.patchNotesHistory.isEmpty())
					throw new IOException("Client generation patch-note history already exists with different content: " + path);
				ConfigTools.writeAtomic(path, fields);
				verify(path, record, patchNotesHistory);
			}
			return;
		}
		Files.createDirectories(path.getParent());
		ConfigTools.writeAtomic(path, fields);
		verify(path, record, patchNotesHistory);
	}

	public Optional<GenerationRecord> read(String generationId) throws IOException {
		return readFields(generationId).map(GenerationRecord::fromFields);
	}

	public Optional<Jsons.CompleteModpackContentFields> readFields(String generationId) throws IOException {
		return readFields(storage.generationManifest(generationId));
	}

	/** Reconstructs the active target from one validated generation record and the persisted selection intent. */
	public Optional<SelectedModpackTarget> readActiveTarget(ClientPlatform platform) throws IOException {
		Objects.requireNonNull(platform, "platform");
		Jsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return Optional.empty();
		Jsons.CompleteModpackContentFields fields = readFields(state.generationId)
				.orElseThrow(() -> new IOException("Active client generation record is missing: " + state.generationId));
		GenerationRecord record = GenerationRecord.fromFields(fields);
		if (!Objects.equals(state.modpackId, record.manifest().modpackId()))
			throw new IOException("Active client state and generation record belong to different modpacks");
		Optional<SelectionIntent> stored = new ClientSelectionStore(storage.selectionFile()).get(state.modpackId);
		return stored.isPresent()
				? Optional.of(SelectedModpackTarget.prepare(fields, null, stored.get(), platform))
				: Optional.of(SelectedModpackTarget.prepareDefault(fields, platform));
	}

	public List<GenerationPatchNoteHistory.Entry> patchNotesHistory(String generationId) throws IOException {
		return readFields(generationId).map(GenerationPatchNoteHistory::fromFields).orElse(List.of());
	}

	public List<String> generationIds() throws IOException {
		if (!Files.exists(storage.recordsDirectory(), LinkOption.NOFOLLOW_LINKS)) return List.of();
		try (Stream<Path> paths = Files.list(storage.recordsDirectory())) {
			return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	/**
	 * Returns the newest valid locally stored generation for each installed modpack.
	 *
	 * <p>
	 * Generation records are immutable and may contain several generations of the same pack.
	 * The pack manager needs one safe catalogue entry, so malformed records are ignored and the
	 * newest valid record wins. A missing or damaged record must not prevent the client from opening
	 * the manager for the other packs.
	 * </p>
	 */
	public List<GenerationRecord> installedRecords() throws IOException {
		Map<String, GenerationRecord> newest = new TreeMap<>();
		for (String generationId : generationIds()) {
			GenerationRecord record;
			try {
				record = read(generationId).orElse(null);
			} catch (IOException | RuntimeException ignored) {
				continue;
			}
			if (record == null) continue;
			String modpackId = record.manifest().modpackId();
			GenerationRecord previous = newest.get(modpackId);
			if (previous == null || record.metadata().createdAt().compareTo(previous.metadata().createdAt()) > 0
					|| record.metadata().createdAt().equals(previous.metadata().createdAt())
							&& record.metadata().generationId().compareTo(previous.metadata().generationId()) > 0)
				newest.put(modpackId, record);
		}
		return List.copyOf(newest.values());
	}

	/** Returns the committed lineage ending at the generation selected by active-state.json. */
	public List<GenerationRecord> lineage(String modpackId, String generationId) throws IOException {
		return readLineage(modpackId, generationId, false);
	}

	/** Returns the downloaded part of the committed lineage; skipped server generations are not client records. */
	public List<GenerationRecord> availableLineage(String modpackId, String generationId) throws IOException {
		return readLineage(modpackId, generationId, true);
	}

	private List<GenerationRecord> readLineage(String modpackId, String generationId, boolean stopAtMissingAncestor) throws IOException {
		ModpackId.requireValid(modpackId);
		List<GenerationRecord> reverse = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		String current = generationId;
		while (current != null && !current.isEmpty()) {
			if (!visited.add(current)) throw new IOException("Client generation lineage contains a cycle");
			Optional<GenerationRecord> optional = read(current);
			if (optional.isEmpty()) {
				if (!stopAtMissingAncestor || reverse.isEmpty())
					throw new IOException((stopAtMissingAncestor ? "Active client generation record is missing: " : "Client generation lineage is incomplete: ") + current);
				break;
			}
			GenerationRecord record = optional.get();
			if (!modpackId.equals(record.manifest().modpackId())) throw new IOException("Client generation lineage crosses modpack IDs");
			reverse.add(record);
			current = record.metadata().parentGenerationId();
		}
		Collections.reverse(reverse);
		return List.copyOf(reverse);
	}

	private static Optional<Jsons.CompleteModpackContentFields> readFields(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generation manifest is not a regular file: " + path);
		try {
			return ConfigTools.read(path, Jsons.CompleteModpackContentFields.class).map(fields -> {
				GenerationRecord.fromFields(fields);
				GenerationPatchNoteHistory.fromFields(fields);
				return fields;
			});
		} catch (RuntimeException e) {
			throw new IOException("Client generation manifest is invalid: " + path, e);
		}
	}

	private static void verify(Path path, GenerationRecord record, List<GenerationPatchNoteHistory.Entry> patchNotesHistory) throws IOException {
		Jsons.CompleteModpackContentFields fields = readFields(path).orElseThrow(() -> new IOException("Stored client generation could not be verified: " + path));
		if (!GenerationRecord.fromFields(fields).equals(record)) throw new IOException("Stored client generation verification failed: " + path);
		if (!GenerationPatchNoteHistory.fromFields(fields).equals(patchNotesHistory)) throw new IOException("Stored client patch-note history verification failed: " + path);
	}
}
