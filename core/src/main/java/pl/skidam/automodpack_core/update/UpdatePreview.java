package pl.skidam.automodpack_core.update;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationMetadata;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupResolution;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.FileKey;
import pl.skidam.automodpack_core.update.UpdatePlan.FileState;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;

public final class UpdatePreview {
	private final UpdatePlan plan;
	private final ChangeSet changeSet;
	private final List<Entry> entries;
	private final GroupConsequences groupConsequences;
	private final String patchNotes;
	private final List<GenerationPatchNoteHistory.Entry> patchNotesHistory;
	private final Mode mode;
	private final Map<String, String> featureNames;

	public UpdatePreview(UpdatePlan plan, List<Entry> entries, GroupConsequences groupConsequences, String patchNotes,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory, Mode mode) {
		this(plan, entries, groupConsequences, patchNotes, patchNotesHistory, mode, createChangeSet(entries, plan.restartReasons()), Map.of());
	}

	private UpdatePreview(UpdatePlan plan, List<Entry> entries, GroupConsequences groupConsequences, String patchNotes,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory, Mode mode, ChangeSet changeSet, Map<String, String> featureNames) {
		this.plan = Objects.requireNonNull(plan, "plan");
		this.changeSet = Objects.requireNonNull(changeSet, "preview change set");
		this.entries = legacyEntries(this.changeSet);
		this.groupConsequences = Objects.requireNonNull(groupConsequences, "groupConsequences");
		this.patchNotes = GenerationMetadata.validateNotes(patchNotes == null ? "" : patchNotes);
		this.patchNotesHistory = List.copyOf(Objects.requireNonNull(patchNotesHistory, "patchNotesHistory"));
		this.mode = Objects.requireNonNull(mode, "mode");
		this.featureNames = Map.copyOf(new TreeMap<>(featureNames == null ? Map.of() : featureNames));
	}

	public UpdatePreview(UpdatePlan plan, List<Entry> entries, GroupConsequences groupConsequences) {
		this(plan, entries, groupConsequences, "", List.of(), Mode.UPDATE);
	}

	public UpdatePreview(UpdatePlan plan, List<Entry> entries, GroupConsequences groupConsequences, String patchNotes) {
		this(plan, entries, groupConsequences, patchNotes, List.of(), Mode.UPDATE);
	}

	public UpdatePlan plan() {
		return plan;
	}

	/** The one canonical, user-visible change set shared by preview and changelog consumers. */
	public List<Entry> entries() {
		return entries;
	}

	/** The canonical logical changes and physical occurrences for this preview. */
	public ChangeSet changeSet() {
		return changeSet;
	}

	public GroupConsequences groupConsequences() {
		return groupConsequences;
	}

	public String patchNotes() {
		return patchNotes;
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

	/** Adds player-facing feature names and exact current ownership to the canonical preview changes. */
	public UpdatePreview withFeatureManifest(GroupManifest manifest) {
		Objects.requireNonNull(manifest, "feature manifest");
		Map<String, String> names = new TreeMap<>();
		Map<String, List<String>> ownersByPath = new TreeMap<>();
		Map<String, GroupManifest.GroupFile> filesByPath = new TreeMap<>();
		manifest.groups().forEach((groupId, group) -> {
			names.put(groupId, group.displayName());
			group.files().forEach((path, file) -> {
				ownersByPath.computeIfAbsent(path, ignored -> new ArrayList<>()).add(groupId);
				filesByPath.putIfAbsent(path, file);
			});
		});
		List<ChangeSet.Change> enriched = new ArrayList<>();
		for (ChangeSet.Change change : changeSet.changes()) {
			List<String> owners = ownersByPath.getOrDefault(change.logicalPath(), List.of());
			GroupManifest.GroupFile currentFile = filesByPath.get(change.logicalPath());
			List<ChangeSet.Occurrence> occurrences = new ArrayList<>();
			for (ChangeSet.Occurrence occurrence : change.occurrences()) {
				List<String> featureIds = owners.isEmpty() ? occurrence.featureIds() : owners;
				String contentKind = currentFile == null ? occurrence.contentKind() : currentFile.type();
				String afterHash = currentFile == null || change.kind() == ChangeSet.Kind.REMOVED ? occurrence.afterHash() : currentFile.sha1();
				occurrences.add(new ChangeSet.Occurrence(occurrence.location(), occurrence.logicalPath(), occurrence.size(), occurrence.beforeHash(), afterHash, contentKind, featureIds, occurrence.references()));
			}
			enriched.add(new ChangeSet.Change(change.logicalPath(), change.kind(), occurrences));
		}
		return new UpdatePreview(plan, entries, groupConsequences, patchNotes, patchNotesHistory, mode, ChangeSet.of(enriched, changeSet.effects()), names);
	}

	public long addedBytes() {
		return bytesOf(Kind.ADDED);
	}

	public long changedBytes() {
		return bytesOf(Kind.CHANGED);
	}

	public long removedBytes() {
		return bytesOf(Kind.REMOVED);
	}

	public long preservedBytes() {
		return entries.stream().filter(entry -> entry.kind.isPreserved()).mapToLong(Entry::size).sum();
	}

	public long uncachedAcquisitionBytes() {
		return plan.operations().stream()
				.filter(operation -> operation.operation() == OperationType.INSTALL_OBJECT && operation.root() == Root.PROJECTION && operation.expectedExistingHash() == null)
				.mapToLong(Operation::expectedSize).sum();
	}

	public Summary summary() {
		ChangeSet.Summary summary = changeSet.summary();
		return new Summary(summary.addedFiles() + summary.modifiedFiles(), summary.removedFiles(), summary.preservedFiles(), summary.unsafeFiles(), summary.effectCount());
	}

	public String latestPatchNotes() {
		return patchNotes;
	}

	public Optional<GenerationPatchNoteHistory.Entry> featuredPatchNotes() {
		if (patchNotes.isBlank()) return Optional.empty();
		for (int index = patchNotesHistory.size() - 1; index >= 0; index--) {
			GenerationPatchNoteHistory.Entry entry = patchNotesHistory.get(index);
			if (entry.patchNotes().equals(patchNotes)) return Optional.of(entry);
		}
		return Optional.empty();
	}

	public Set<RestartReason> restartReasons() {
		return plan.restartReasons();
	}

	/** Conflict rows are kept separate from file rows so a later screen can offer restore/keep actions. */
	public List<Conflict> conflicts() {
		return plan.conflicts();
	}

	private long bytesOf(Kind kind) {
		return entries.stream().filter(entry -> entry.kind == kind).mapToLong(Entry::size).sum();
	}

	private static ChangeSet createChangeSet(List<Entry> entries, Set<RestartReason> restartReasons) {
		List<ChangeSet.Change> changes = new ArrayList<>();
		for (Entry entry : List.copyOf(Objects.requireNonNull(entries, "entries"))) {
			ChangeSet.Occurrence occurrence = new ChangeSet.Occurrence(entry.root.name(), entry.relativePath(), entry.size());
			changes.add(new ChangeSet.Change(entry.relativePath(), canonicalKind(entry.kind), List.of(occurrence)));
		}
		List<ChangeSet.Effect> effects = new ArrayList<>();
		if (restartReasons != null) for (RestartReason reason : restartReasons) effects.add(new ChangeSet.Effect("restart", reason.name()));
		return ChangeSet.of(changes, effects);
	}

	private static ChangeSet createChangeSet(List<Entry> entries, Set<RestartReason> restartReasons, Map<FileKey, FileState> originalFiles,
			ModpackJsons.ModpackContentFields target, OwnershipLedger ledger) {
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetFiles = new TreeMap<>();
		if (target.list != null) for (ModpackJsons.ModpackContentFields.ModpackContentItem item : target.list) targetFiles.put(UpdatePlanner.normalize(item.file), item);
		List<ChangeSet.Change> changes = new ArrayList<>();
		for (Entry entry : entries) {
			FileKey key = new FileKey(entry.root(), entry.relativePath());
			FileState before = originalFiles.get(key);
			ModpackJsons.ModpackContentFields.ModpackContentItem after = targetFiles.get(entry.relativePath());
			OwnershipLedger.Entry ownership = ledger.entries().get(entry.relativePath());
			String beforeHash = before == null || !HashUtils.isSha1(before.sha1()) ? null : before.sha1();
			String afterHash = after == null || !HashUtils.isSha1(after.sha1) ? null : after.sha1;
			String contentKind = after == null ? null : after.type;
			List<String> featureIds = ownership == null ? List.of() : List.copyOf(ownership.historicalGroupIds());
			ChangeSet.Occurrence occurrence = new ChangeSet.Occurrence(entry.root.name(), entry.relativePath(), entry.size(), beforeHash, afterHash, contentKind, featureIds, List.of());
			changes.add(new ChangeSet.Change(entry.relativePath(), canonicalKind(entry.kind()), List.of(occurrence)));
		}
		List<ChangeSet.Effect> effects = new ArrayList<>();
		for (RestartReason reason : restartReasons) effects.add(new ChangeSet.Effect("restart", reason.name()));
		return ChangeSet.of(changes, effects);
	}

	private static List<Entry> legacyEntries(ChangeSet changeSet) {
		List<Entry> entries = new ArrayList<>();
		for (ChangeSet.Change change : changeSet.changes()) {
			ChangeSet.Occurrence occurrence = change.occurrences().stream().min(Comparator.comparingInt(entry -> root(entry.location()).ordinal())).orElseThrow();
			long size = change.occurrences().stream().mapToLong(ChangeSet.Occurrence::size).max().orElseThrow();
			entries.add(new Entry(legacyKind(change.kind()), root(occurrence.location()), change.logicalPath(), size));
		}
		entries.sort(Comparator.comparing((Entry entry) -> entry.kind.sortBucket()).thenComparing(entry -> entry.kind.ordinal()).thenComparing(entry -> entry.root.ordinal())
				.thenComparing(Entry::relativePath));
		return List.copyOf(entries);
	}

	private static ChangeSet.Kind canonicalKind(Kind kind) {
		return switch (kind) {
			case ADDED -> ChangeSet.Kind.ADDED;
			case CHANGED -> ChangeSet.Kind.MODIFIED;
			case REMOVED -> ChangeSet.Kind.REMOVED;
			case PRESERVED_CHANGED -> ChangeSet.Kind.PRESERVED_CHANGED;
			case PRESERVED_UNAVAILABLE -> ChangeSet.Kind.PRESERVED_UNAVAILABLE;
			case PRESERVED_OUTSIDE -> ChangeSet.Kind.PRESERVED_OUTSIDE;
			case UNSAFE -> ChangeSet.Kind.UNSAFE;
		};
	}

	private static Kind legacyKind(ChangeSet.Kind kind) {
		return switch (kind) {
			case ADDED -> Kind.ADDED;
			case MODIFIED, METADATA_ONLY -> Kind.CHANGED;
			case REMOVED -> Kind.REMOVED;
			case PRESERVED_CHANGED -> Kind.PRESERVED_CHANGED;
			case PRESERVED_UNAVAILABLE -> Kind.PRESERVED_UNAVAILABLE;
			case PRESERVED_OUTSIDE, PRESERVED -> Kind.PRESERVED_OUTSIDE;
			case UNSAFE -> Kind.UNSAFE;
		};
	}

	private static Root root(String location) {
		try {
			return Root.valueOf(location);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("Preview change has an unknown physical root: " + location, e);
		}
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, ModpackJsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal) {
		return create(plan, originalFiles, target, selection, removal, null, "", List.of());
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, ModpackJsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal, String patchNotes) {
		return create(plan, originalFiles, target, selection, removal, null, patchNotes, List.of());
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, ModpackJsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal, ClientStorageJsons.ClientBaselineFields baseline) {
		return create(plan, originalFiles, target, selection, removal, baseline, "", List.of());
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, ModpackJsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal, ClientStorageJsons.ClientBaselineFields baseline, String patchNotes) {
		return create(plan, originalFiles, target, selection, removal, baseline, patchNotes, List.of());
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, ModpackJsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal, ClientStorageJsons.ClientBaselineFields baseline, String patchNotes,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory) {
		return create(plan, originalFiles, target, selection, removal ? Mode.REMOVAL : Mode.UPDATE, baseline, patchNotes, patchNotesHistory);
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, ModpackJsons.ModpackContentFields target,
			ResolvedSelection selection, Mode mode, ClientStorageJsons.ClientBaselineFields baseline, String patchNotes,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(originalFiles, "originalFiles");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(mode, "mode");
		boolean removal = mode != Mode.UPDATE;
		Map<FileKey, Operation> operations = plan.operations().stream()
				.collect(Collectors.toMap(operation -> new FileKey(operation.root(), operation.relativePath()), operation -> operation));
		Map<String, ClientStorageJsons.ClientBaselineFields.EntryFields> baselineEntries = baselineEntries(baseline);
		List<Entry> entries = new ArrayList<>();
		for (Operation operation : plan.operations()) {
			FileKey key = new FileKey(operation.root(), operation.relativePath());
			FileState previous = originalFiles.get(key);
			if (operation.operation() == OperationType.INSTALL_OBJECT) {
				Kind kind = previous == null || !previous.regularFile() ? Kind.ADDED : Kind.CHANGED;
				entries.add(new Entry(kind, key.root(), key.relativePath(), operation.expectedSize()));
			} else if (operation.operation() == OperationType.DELETE) {
				long size = previous == null ? 0 : Math.max(0, previous.size());
				entries.add(new Entry(Kind.REMOVED, key.root(), key.relativePath(), size));
			}
		}

		Set<String> targetPaths = new TreeSet<>();
		if (!removal && target.list != null) for (var item : target.list) targetPaths.add(UpdatePlanner.normalize(item.file));
		OwnershipLedger ledger = OwnershipLedger.fromFields(target.ownershipLedger);
		for (OwnershipLedger.Entry ledgerEntry : ledger.entries().values()) {
			if (targetPaths.contains(ledgerEntry.logicalPath())) continue;
			Optional<FileKey> optionalKey = UpdatePlanner.managedCleanupKey(ledgerEntry.logicalPath());
			if (optionalKey.isEmpty()) continue;
			FileKey key = optionalKey.get();
			FileState current = originalFiles.get(key);
			if (current == null || operations.containsKey(key)) continue;
			if (removal && baselineMatches(current, baselineEntries.get(ledgerEntry.logicalPath()))) continue;
			if (!current.regularFile()) {
				entries.add(new Entry(Kind.UNSAFE, key.root(), key.relativePath(), Math.max(0, current.size())));
				continue;
			}
			String currentHash = current.sha1();
			if (!HashUtils.isSha1(currentHash) || current.size() < 0) {
				entries.add(new Entry(Kind.PRESERVED_UNAVAILABLE, key.root(), key.relativePath(), Math.max(0, current.size())));
				continue;
			}
			OwnershipLedger.Content content = new OwnershipLedger.Content(currentHash.toLowerCase(Locale.ROOT), current.size());
			Kind kind = ledgerEntry.historicalHashes().contains(content)
					? removal ? Kind.PRESERVED_UNAVAILABLE : Kind.PRESERVED_OUTSIDE
					: Kind.PRESERVED_CHANGED;
			entries.add(new Entry(kind, key.root(), key.relativePath(), current.size()));
		}

		entries.sort(Comparator.comparing((Entry entry) -> entry.kind.sortBucket()).thenComparing(entry -> entry.kind.ordinal()).thenComparing(entry -> entry.root.ordinal())
				.thenComparing(Entry::relativePath));
		GroupConsequences consequences = selection == null ? new GroupConsequences(Set.of(), Set.of(), Set.of()) : consequences(selection);
		return new UpdatePreview(plan, entries, consequences, patchNotes, patchNotesHistory, mode,
				createChangeSet(entries, plan.restartReasons(), originalFiles, target, ledger), Map.of());
	}

	private static GroupConsequences consequences(ResolvedSelection selection) {
		return new GroupConsequences(selection.intent().requestedGroups(), selection.selectedGroups(), selection.staleRequestedGroups(), selection.groupResolutions());
	}

	private static Map<String, ClientStorageJsons.ClientBaselineFields.EntryFields> baselineEntries(ClientStorageJsons.ClientBaselineFields baseline) {
		if (baseline == null || baseline.entries == null) return Map.of();
		Map<String, ClientStorageJsons.ClientBaselineFields.EntryFields> entries = new HashMap<>();
		for (var entry : baseline.entries) {
			if (entry != null && entry.logicalPath != null) entries.put(UpdatePlanner.normalize(entry.logicalPath), entry);
		}
		return entries;
	}

	private static boolean baselineMatches(FileState current, ClientStorageJsons.ClientBaselineFields.EntryFields baseline) {
		return baseline != null && !baseline.absent && HashUtils.isSha1(baseline.objectHash)
				&& baseline.size >= 0 && current.regularFile() && baseline.size == current.size() && baseline.objectHash.equalsIgnoreCase(current.sha1());
	}

	public record Entry(Kind kind, Root root, String relativePath, long size) {
		public Entry {
			kind = Objects.requireNonNull(kind, "kind");
			root = Objects.requireNonNull(root, "root");
			if (relativePath == null || relativePath.isBlank()) throw new IllegalArgumentException("Preview path is missing");
			if (size < 0) throw new IllegalArgumentException("Preview size is negative");
		}
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

	public enum Kind {
		ADDED(SummaryBucket.CHANGED, SortBucket.CHANGED, "+ "),
		CHANGED(SummaryBucket.CHANGED, SortBucket.CHANGED, "~ "),
		REMOVED(SummaryBucket.REMOVED, SortBucket.REMOVED, "- "),
		PRESERVED_CHANGED(SummaryBucket.PRESERVED, SortBucket.PRESERVED, "  "),
		PRESERVED_UNAVAILABLE(SummaryBucket.PRESERVED, SortBucket.PRESERVED, "  "),
		PRESERVED_OUTSIDE(SummaryBucket.PRESERVED, SortBucket.PRESERVED, "  "),
		UNSAFE(SummaryBucket.UNSAFE, SortBucket.UNSAFE, "! ");

		private final SummaryBucket summaryBucket;
		private final SortBucket sortBucket;
		private final String displaySymbol;

		Kind(SummaryBucket summaryBucket, SortBucket sortBucket, String displaySymbol) {
			this.summaryBucket = summaryBucket;
			this.sortBucket = sortBucket;
			this.displaySymbol = displaySymbol;
		}

		public boolean isPreserved() {
			return this.summaryBucket == SummaryBucket.PRESERVED;
		}

		public SummaryBucket summaryBucket() {
			return this.summaryBucket;
		}

		public SortBucket sortBucket() {
			return this.sortBucket;
		}

		public String displaySymbol() {
			return this.displaySymbol;
		}
	}

	public enum SummaryBucket {
		CHANGED,
		REMOVED,
		PRESERVED,
		UNSAFE
	}

	public enum SortBucket {
		UNSAFE,
		REMOVED,
		CHANGED,
		PRESERVED
	}
}
