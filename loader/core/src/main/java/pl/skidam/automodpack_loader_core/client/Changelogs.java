package pl.skidam.automodpack_loader_core.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;

public class Changelogs {
	private final Map<String, FileChange> changedFiles = new LinkedHashMap<>();
	private final Map<String, FileChange> removedFiles = new LinkedHashMap<>();
	private String latestPatchNotes = "";
	private List<GenerationPatchNoteHistory.Entry> patchNotesHistory = List.of();
	private List<String> restartReasons = List.of();

	public Map<String, FileChange> changedFiles() {
		return Collections.unmodifiableMap(changedFiles);
	}

	public Map<String, FileChange> removedFiles() {
		return Collections.unmodifiableMap(removedFiles);
	}

	public void clear() {
		changedFiles.clear();
		removedFiles.clear();
		latestPatchNotes = "";
		patchNotesHistory = List.of();
		restartReasons = List.of();
	}

	public void replaceWith(UpdatePreview preview, Map<UpdatePlan.FileKey, List<String>> mainPageUrls) {
		Objects.requireNonNull(preview, "preview");
		Objects.requireNonNull(mainPageUrls, "main page URLs");
		clear();
		latestPatchNotes = preview.latestPatchNotes();
		patchNotesHistory = preview.patchNotesHistory();
		for (UpdatePreview.Entry entry : preview.entries()) {
			FileChange change = new FileChange(entry.relativePath(), mainPageUrls.getOrDefault(new UpdatePlan.FileKey(entry.root(), entry.relativePath()), List.of()));
			switch (entry.kind()) {
				case ADDED, CHANGED -> changedFiles.put(entry.relativePath(), change);
				case REMOVED -> removedFiles.put(entry.relativePath(), change);
				default -> {
				}
			}
		}
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

	public record FileChange(String logicalPath, List<String> mainPageUrls) {
		public FileChange {
			if (logicalPath == null || logicalPath.isBlank()) throw new IllegalArgumentException("Changelog path is missing");
			mainPageUrls = List.copyOf(mainPageUrls);
		}
	}
}
