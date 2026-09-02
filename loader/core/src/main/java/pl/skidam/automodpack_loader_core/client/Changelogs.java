package pl.skidam.automodpack_loader_core.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.update.UpdatePreview;

public class Changelogs {
	private String latestPatchNotes = "";
	private List<GenerationPatchNoteHistory.Entry> patchNotesHistory = List.of();
	private List<String> restartReasons = List.of();
	private ChangeSet changeSet = ChangeSet.empty();

	/** Changed files derived on demand from the canonical change set. */
	public Map<String, FileChange> changedFiles() {
		return fileChanges(ChangeSet.Kind.ADDED, ChangeSet.Kind.MODIFIED);
	}

	/** Removed files derived on demand from the canonical change set. */
	public Map<String, FileChange> removedFiles() {
		return fileChanges(ChangeSet.Kind.REMOVED);
	}

	private Map<String, FileChange> fileChanges(ChangeSet.Kind... kinds) {
		Set<ChangeSet.Kind> wanted = Set.of(kinds);
		Map<String, FileChange> files = new LinkedHashMap<>();
		for (ChangeSet.Change change : changeSet.changes()) if (wanted.contains(change.kind())) files.put(change.logicalPath(), new FileChange(change.logicalPath(), references(change)));
		return Collections.unmodifiableMap(files);
	}

	/** The complete applied change set, including every physical occurrence and source reference. */
	public ChangeSet changeSet() {
		return changeSet;
	}

	public void clear() {
		latestPatchNotes = "";
		patchNotesHistory = List.of();
		restartReasons = List.of();
		changeSet = ChangeSet.empty();
	}

	public void replaceWith(UpdatePreview preview) {
		Objects.requireNonNull(preview, "preview");
		latestPatchNotes = preview.latestPatchNotes();
		patchNotesHistory = preview.patchNotesHistory();
		changeSet = preview.changeSet();
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
