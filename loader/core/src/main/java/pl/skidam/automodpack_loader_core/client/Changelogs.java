package pl.skidam.automodpack_loader_core.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import pl.skidam.automodpack_core.modpack.generation.GenerationMetadata;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.update.UpdatePlan;

public class Changelogs {
	private final Map<UpdatePlan.FileKey, FileChange> changedFiles = new LinkedHashMap<>();
	private final Map<UpdatePlan.FileKey, FileChange> removedFiles = new LinkedHashMap<>();
	private String latestPatchNotes = "";
	private List<GenerationPatchNoteHistory.Entry> patchNotesHistory = List.of();
	private List<String> restartReasons = List.of();

	public Map<UpdatePlan.FileKey, FileChange> changedFiles() {
		return Collections.unmodifiableMap(changedFiles);
	}

	public Map<UpdatePlan.FileKey, FileChange> removedFiles() {
		return Collections.unmodifiableMap(removedFiles);
	}

	public void clear() {
		changedFiles.clear();
		removedFiles.clear();
		latestPatchNotes = "";
		patchNotesHistory = List.of();
		restartReasons = List.of();
	}

	public void recordChanged(UpdatePlan.FileKey file, List<String> mainPageUrls) {
		Objects.requireNonNull(file, "file");
		changedFiles.put(file, new FileChange(file, mainPageUrls));
	}

	public void recordRemoved(UpdatePlan.FileKey file, List<String> mainPageUrls) {
		Objects.requireNonNull(file, "file");
		removedFiles.put(file, new FileChange(file, mainPageUrls));
	}

	public void setPatchNotes(String latestPatchNotes, List<GenerationPatchNoteHistory.Entry> patchNotesHistory) {
		this.latestPatchNotes = GenerationMetadata.validateNotes(latestPatchNotes == null ? "" : latestPatchNotes);
		this.patchNotesHistory = List.copyOf(Objects.requireNonNull(patchNotesHistory, "patch notes history"));
	}

	public String latestPatchNotes() {
		if (!latestPatchNotes.isBlank()) return latestPatchNotes;
		return GenerationPatchNoteHistory.latestNotes(patchNotesHistory);
	}

	public List<GenerationPatchNoteHistory.Entry> patchNotesHistory() {
		return patchNotesHistory;
	}

	public List<String> restartReasons() {
		return restartReasons;
	}

	public void setRestartReasons(List<String> restartReasons) {
		this.restartReasons = List.copyOf(Objects.requireNonNull(restartReasons, "restart reasons"));
	}

	public record FileChange(UpdatePlan.FileKey file, List<String> mainPageUrls) {
		public FileChange {
			file = Objects.requireNonNull(file, "file");
			mainPageUrls = List.copyOf(mainPageUrls);
		}
	}
}
