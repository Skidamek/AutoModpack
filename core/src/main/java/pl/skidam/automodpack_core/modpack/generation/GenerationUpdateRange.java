package pl.skidam.automodpack_core.modpack.generation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The exact ordered generation range applied by one client update. */
public record GenerationUpdateRange(List<GenerationPatchNoteHistory.Entry> generations, boolean complete) {
	public GenerationUpdateRange {
		generations = List.copyOf(Objects.requireNonNull(generations, "generations"));
	}

	public static GenerationUpdateRange between(List<GenerationPatchNoteHistory.Entry> history, String installedGenerationId, String targetGenerationId) {
		Objects.requireNonNull(history, "history");
		targetGenerationId = GenerationMetadata.requireDigest(targetGenerationId, "target generation ID");
		int targetIndex = indexOf(history, targetGenerationId);
		if (targetIndex < 0) throw new IllegalArgumentException("Generation history does not contain the target generation");
		if (installedGenerationId == null || installedGenerationId.isBlank()) return new GenerationUpdateRange(List.of(history.get(targetIndex)), true);
		installedGenerationId = GenerationMetadata.requireDigest(installedGenerationId, "installed generation ID");
		int installedIndex = indexOf(history, installedGenerationId);
		if (installedIndex < 0) return new GenerationUpdateRange(List.of(history.get(targetIndex)), false);
		if (installedIndex > targetIndex) throw new IllegalArgumentException("Installed generation is newer than the update target");
		return new GenerationUpdateRange(history.subList(installedIndex + 1, targetIndex + 1), true);
	}

	public boolean spansMultipleGenerations() {
		return generations.size() > 1;
	}

	public Optional<GenerationPatchNoteHistory.Entry> featuredNotes() {
		if (generations.isEmpty()) return Optional.empty();
		if (!spansMultipleGenerations()) return generations.get(0).patchNotes().isBlank() ? Optional.empty() : Optional.of(generations.get(0));
		for (int index = generations.size() - 1; index >= 0; index--) {
			GenerationPatchNoteHistory.Entry generation = generations.get(index);
			if (!generation.patchNotes().isBlank()) return Optional.of(generation);
		}
		return Optional.empty();
	}

	private static int indexOf(List<GenerationPatchNoteHistory.Entry> history, String generationId) {
		for (int index = 0; index < history.size(); index++) {
			GenerationPatchNoteHistory.Entry entry = Objects.requireNonNull(history.get(index), "generation history entry");
			if (entry.generationId().equals(generationId)) return index;
		}
		return -1;
	}
}
