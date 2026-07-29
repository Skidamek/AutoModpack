package pl.skidam.automodpack_core.modpack;

import java.util.Objects;

import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;

public record GenerationResult(GenerationStatus status, GenerationRecord current, Throwable failure) {
	public GenerationResult {
		status = Objects.requireNonNull(status);
		if (status == GenerationStatus.FAILED && failure == null) throw new IllegalArgumentException("Failed generation requires a failure");
		if (status == GenerationStatus.FAILED && current != null) throw new IllegalArgumentException("Failed generation cannot have a current record");
		if (status != GenerationStatus.FAILED && current == null) throw new IllegalArgumentException("Successful generation requires a current record");
		if (status != GenerationStatus.FAILED && failure != null) throw new IllegalArgumentException("Successful generation cannot have a failure");
	}

	public boolean succeeded() {
		return status != GenerationStatus.FAILED;
	}
}
