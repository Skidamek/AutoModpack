package pl.skidam.automodpack_core.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.generation.GenerationMetadata;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupResolution;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

/** Player-facing projection of one immutable reconciliation decision. */
public final class UpdatePreview {
	private final UpdatePlan decision;
	private final ChangeSet changeSet;
	private final GroupConsequences groupConsequences;
	private final String patchNotes;
	private final List<GenerationPatchNoteHistory.Entry> patchNotesHistory;
	private final Mode mode;
	private final Map<String, String> featureNames;

	private UpdatePreview(UpdatePlan decision, GroupConsequences groupConsequences, String patchNotes,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory, Mode mode, ChangeSet changeSet, Map<String, String> featureNames) {
		this.decision = Objects.requireNonNull(decision, "reconciliation decision");
		this.changeSet = Objects.requireNonNull(changeSet, "reconciliation consequences");
		this.groupConsequences = Objects.requireNonNull(groupConsequences, "group consequences");
		this.patchNotes = GenerationMetadata.validateNotes(patchNotes == null ? "" : patchNotes);
		this.patchNotesHistory = List.copyOf(Objects.requireNonNull(patchNotesHistory, "patch notes history"));
		this.mode = Objects.requireNonNull(mode, "mode");
		this.featureNames = Map.copyOf(new TreeMap<>(featureNames == null ? Map.of() : featureNames));
	}

	public static UpdatePreview create(UpdatePlan decision, ResolvedSelection selection, Mode mode, String patchNotes,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory) {
		Objects.requireNonNull(decision, "reconciliation decision");
		GroupConsequences consequences = selection == null
				? new GroupConsequences(Set.of(), Set.of(), Set.of())
				: new GroupConsequences(selection.intent().requestedGroups(), selection.selectedGroups(), selection.staleRequestedGroups(), selection.groupResolutions());
		return new UpdatePreview(decision, consequences, patchNotes, patchNotesHistory, mode, decision.consequences(), Map.of());
	}

	public static UpdatePreview create(UpdatePlan decision, ResolvedSelection selection, Mode mode) {
		return create(decision, selection, mode, "", List.of());
	}

	/** The canonical logical consequences decided during reconciliation planning. */
	public ChangeSet changeSet() {
		return changeSet;
	}

	/** The immutable reconciliation decision this preview projects. */
	public UpdatePlan plan() {
		return decision;
	}

	public GroupConsequences groupConsequences() {
		return groupConsequences;
	}

	public List<GenerationPatchNoteHistory.Entry> patchNotesHistory() {
		return patchNotesHistory;
	}

	public Mode mode() {
		return mode;
	}

	public Map<String, String> featureNames() {
		return featureNames;
	}

	/** Adds presentation names without reclassifying any planned disposition. */
	public UpdatePreview withFeatureManifest(GroupManifest manifest) {
		Objects.requireNonNull(manifest, "feature manifest");
		Map<String, String> names = new TreeMap<>();
		Map<String, List<String>> ownersByPath = new TreeMap<>();
		manifest.groups().forEach((groupId, group) -> {
			names.put(groupId, group.displayName());
			group.files().keySet().forEach(path -> ownersByPath.computeIfAbsent(path, ignored -> new ArrayList<>()).add(groupId));
		});
		List<ChangeSet.Change> changes = new ArrayList<>(changeSet.changes().size());
		for (ChangeSet.Change change : changeSet.changes()) {
			List<String> owners = ownersByPath.get(change.logicalPath());
			if (owners == null) {
				changes.add(change);
				continue;
			}
			List<ChangeSet.Occurrence> occurrences = change.occurrences().stream()
					.map(occurrence -> new ChangeSet.Occurrence(occurrence.location(), occurrence.logicalPath(), occurrence.size(), occurrence.beforeHash(), occurrence.afterHash(), occurrence.contentKind(), owners,
							occurrence.references()))
					.toList();
			changes.add(new ChangeSet.Change(change.logicalPath(), change.kind(), occurrences));
		}
		return new UpdatePreview(decision, groupConsequences, patchNotes, patchNotesHistory, mode, ChangeSet.of(changes, changeSet.effects()), names);
	}

	public UpdatePreview withReferences(ChangeSet.ReferenceProvider provider) {
		return new UpdatePreview(decision, groupConsequences, patchNotes, patchNotesHistory, mode, changeSet.withReferences(provider), featureNames);
	}

	public long addedBytes() {
		return bytesOf(ChangeSet.Kind.ADDED);
	}

	public long changedBytes() {
		return bytesOf(ChangeSet.Kind.MODIFIED);
	}

	public long uncachedAcquisitionBytes() {
		return decision.operations().stream()
				.filter(operation -> operation.operation() == OperationType.INSTALL_OBJECT && operation.root() == Root.PROJECTION && operation.expectedExistingHash() == null)
				.mapToLong(UpdatePlan.Operation::expectedSize).sum();
	}

	public Summary summary() {
		ChangeSet.Summary summary = changeSet.summary();
		return new Summary(summary.addedFiles() + summary.modifiedFiles(), summary.removedFiles(), summary.preservedFiles(), summary.unsafeFiles(), summary.effectCount());
	}

	public String latestPatchNotes() {
		return patchNotes;
	}

	public Set<RestartReason> restartReasons() {
		return decision.restartReasons();
	}

	public List<Conflict> conflicts() {
		return decision.conflicts();
	}

	private long bytesOf(ChangeSet.Kind kind) {
		return changeSet.changes().stream().filter(change -> change.kind() == kind).mapToLong(UpdatePreview::largestOccurrence).sum();
	}

	private static long largestOccurrence(ChangeSet.Change change) {
		return change.occurrences().stream().mapToLong(ChangeSet.Occurrence::size).max().orElse(0);
	}

	public record Summary(int changedFiles, int removedFiles, int preservedFiles, int unsafeFiles, int otherEffects) {}

	public record GroupConsequences(Set<String> explicitGroups, Set<String> resolvedGroups, Set<String> staleGroups, Map<String, GroupResolution> resolutions) {
		public GroupConsequences(Set<String> explicitGroups, Set<String> resolvedGroups, Set<String> staleGroups) {
			this(explicitGroups, resolvedGroups, staleGroups, Map.of());
		}

		public GroupConsequences {
			explicitGroups = immutable(explicitGroups);
			resolvedGroups = immutable(resolvedGroups);
			staleGroups = immutable(staleGroups);
			resolutions = Map.copyOf(new TreeMap<>(resolutions == null ? Map.of() : resolutions));
		}

		private static Set<String> immutable(Set<String> values) {
			return Set.copyOf(new TreeSet<>(values == null ? Set.of() : values));
		}
	}

	public enum Mode {
		UPDATE,
		DEACTIVATION,
		REMOVAL
	}
}
