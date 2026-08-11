package pl.skidam.automodpack_loader_core.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.update.UpdatePreview;

public class Changelogs {
	private final Map<String, FileChange> changedFiles = new LinkedHashMap<>();
	private final Map<String, FileChange> removedFiles = new LinkedHashMap<>();
	private String latestPatchNotes = "";
	private List<GenerationPatchNoteHistory.Entry> patchNotesHistory = List.of();
	private List<String> restartReasons = List.of();
	private ChangeSet changeSet = ChangeSet.empty();

	public Map<String, FileChange> changedFiles() {
		return Collections.unmodifiableMap(changedFiles);
	}

	public Map<String, FileChange> removedFiles() {
		return Collections.unmodifiableMap(removedFiles);
	}

	/** The complete applied change set, including every physical occurrence and source reference. */
	public ChangeSet changeSet() {
		return changeSet;
	}

	public void clear() {
		changedFiles.clear();
		removedFiles.clear();
		latestPatchNotes = "";
		patchNotesHistory = List.of();
		restartReasons = List.of();
		changeSet = ChangeSet.empty();
	}

	public void replaceWith(UpdatePreview preview, Map<UpdatePlan.FileKey, List<String>> mainPageUrls) {
		Objects.requireNonNull(preview, "preview");
		Objects.requireNonNull(mainPageUrls, "main page URLs");
		clear();
		latestPatchNotes = preview.latestPatchNotes();
		patchNotesHistory = preview.patchNotesHistory();
		changeSet = preview.changeSet().withReferences((location, path) -> references(location, path, mainPageUrls));
		for (ChangeSet.Change change : changeSet.changes()) {
			FileChange fileChange = new FileChange(change.logicalPath(), references(change));
			switch (change.kind()) {
				case ADDED, MODIFIED -> changedFiles.put(change.logicalPath(), fileChange);
				case REMOVED -> removedFiles.put(change.logicalPath(), fileChange);
				default -> {
				}
			}
		}
	}

	public String latestPatchNotes() {
		return latestPatchNotes;
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

	private static List<String> references(String location, String path, Map<UpdatePlan.FileKey, List<String>> mainPageUrls) {
		try {
			return mainPageUrls.getOrDefault(new UpdatePlan.FileKey(Root.valueOf(location), path), List.of());
		} catch (IllegalArgumentException e) {
			return List.of();
		}
	}

	private static List<String> references(ChangeSet.Change change) {
		List<String> references = new ArrayList<>();
		for (ChangeSet.Occurrence occurrence : change.occurrences())
			for (String reference : occurrence.references()) if (!references.contains(reference)) references.add(reference);
		return List.copyOf(references);
	}

	public record FileChange(String logicalPath, List<String> mainPageUrls) {
		public FileChange {
			if (logicalPath == null || logicalPath.isBlank()) throw new IllegalArgumentException("Changelog path is missing");
			mainPageUrls = List.copyOf(mainPageUrls == null ? List.of() : mainPageUrls);
		}
	}
}
