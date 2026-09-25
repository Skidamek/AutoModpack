package pl.skidam.automodpack_core.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.update.UpdatePreview;

public class Changelogs {
	private String latestPatchNotes = "";
	private List<JournalEntry> journal = List.of();
	private List<String> restartReasons = List.of();
	private String requiredMcVersion = "";
	private String requiredLoader = "";
	private ChangeSet changeSet = ChangeSet.empty();

	/** Changed files derived on demand from the canonical change set. */
	public Map<String, FileChange> changedFiles() {
		return fileChanges(ChangeSet.Kind.ADDED, ChangeSet.Kind.MODIFIED);
	}

	/** Removed files derived on demand from the canonical change set. */
	public Map<String, FileChange> removedFiles() {
		return fileChanges(ChangeSet.Kind.REMOVED);
	}

	/** Logical paths of added, modified, or removed files; the in-game restart policy reads these. */
	public Set<String> changedOrRemovedPaths() {
		Set<String> paths = new LinkedHashSet<>();
		paths.addAll(changedFiles().keySet());
		paths.addAll(removedFiles().keySet());
		return paths;
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

	public void replaceWith(UpdatePreview preview) {
		Objects.requireNonNull(preview, "preview");
		latestPatchNotes = preview.latestPatchNotes();
		journal = preview.journal();
		changeSet = preview.changeSet();
		setRestartReasons(preview.restartReasons().stream().map(Enum::name).toList());
	}

	public String latestPatchNotes() {
		return latestPatchNotes;
	}

	public List<JournalEntry> journal() {
		return journal;
	}

	/** True when any journal entry in the tail carries patch notes worth showing. */
	public static boolean hasNotes(List<JournalEntry> journal) {
		return journal.stream().anyMatch(entry -> !entry.notes().isBlank());
	}

	public List<String> restartReasons() {
		return restartReasons;
	}

	public void setRestartReasons(List<String> restartReasons) {
		this.restartReasons = List.copyOf(Objects.requireNonNull(restartReasons, "restart reasons"));
	}

	/** Args for one restart-reason lang key, empty when the key has no placeholders. */
	public List<String> restartReasonArgs(String reason) {
		if (!"MANUAL_VERSION_SWITCH".equals(reason) || requiredMcVersion.isBlank() || requiredLoader.isBlank()) return List.of();
		return List.of(requiredMcVersion, requiredLoader);
	}

	/** The pack versions a manual switch must name on the restart screen. */
	public void setRequiredLauncherVersions(String mcVersion, String loader, String loaderVersion) {
		requiredMcVersion = mcVersion == null ? "" : mcVersion;
		if (loader == null || loader.isBlank()) {
			requiredLoader = "";
			return;
		}
		requiredLoader = loaderVersion == null || loaderVersion.isBlank() ? loader : loader + " " + loaderVersion;
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
