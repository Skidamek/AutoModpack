package pl.skidam.automodpack_core.update;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationMetadata;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.FileKey;
import pl.skidam.automodpack_core.update.UpdatePlan.FileState;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;

public record UpdatePreview(
		UpdatePlan plan,
		List<Entry> entries,
		GroupConsequences groupConsequences,
		String patchNotes,
		List<GenerationPatchNoteHistory.Entry> patchNotesHistory,
		Mode mode) {
	public UpdatePreview {
		plan = Objects.requireNonNull(plan, "plan");
		entries = List.copyOf(entries);
		groupConsequences = Objects.requireNonNull(groupConsequences, "groupConsequences");
		patchNotes = GenerationMetadata.validateNotes(patchNotes == null ? "" : patchNotes);
		patchNotesHistory = List.copyOf(Objects.requireNonNull(patchNotesHistory, "patchNotesHistory"));
		mode = Objects.requireNonNull(mode, "mode");
	}

	public UpdatePreview(UpdatePlan plan, List<Entry> entries, GroupConsequences groupConsequences) {
		this(plan, entries, groupConsequences, "", List.of(), Mode.UPDATE);
	}

	public UpdatePreview(UpdatePlan plan, List<Entry> entries, GroupConsequences groupConsequences, String patchNotes) {
		this(plan, entries, groupConsequences, patchNotes, List.of(), Mode.UPDATE);
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
		Set<FileKey> changed = new HashSet<>();
		Set<FileKey> removed = new HashSet<>();
		Set<FileKey> preserved = new HashSet<>();
		Set<FileKey> unsafe = new HashSet<>();
		for (Entry entry : entries) {
			FileKey key = new FileKey(entry.root, entry.relativePath);
			switch (entry.kind.summaryBucket()) {
				case CHANGED -> changed.add(key);
				case REMOVED -> removed.add(key);
				case PRESERVED -> preserved.add(key);
				case UNSAFE -> unsafe.add(key);
			}
		}
		Set<String> otherEffects = new TreeSet<>();
		for (RestartReason reason : plan.restartReasons()) otherEffects.add(reason.name());
		return new Summary(changed.size(), removed.size(), preserved.size(), unsafe.size(), otherEffects.size());
	}

	/** Returns one row per physical file key, preferring an effective change over preservation information. */
	public List<Entry> displayEntries() {
		Map<FileKey, Entry> unique = new TreeMap<>(Comparator.comparing((FileKey key) -> key.root().ordinal()).thenComparing(FileKey::relativePath));
		for (Entry entry : entries) {
			FileKey key = new FileKey(entry.root, entry.relativePath);
			Entry previous = unique.get(key);
			if (previous == null || entry.kind.sortBucket().compareTo(previous.kind.sortBucket()) < 0) unique.put(key, entry);
		}
		return unique.values().stream().sorted(Comparator.comparing((Entry entry) -> entry.kind.ordinal()).thenComparing(entry -> entry.root.ordinal())
				.thenComparing(Entry::relativePath)).toList();
	}

	public String latestPatchNotes() {
		if (!patchNotes.isBlank()) return patchNotes;
		return GenerationPatchNoteHistory.latestNotes(patchNotesHistory);
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
		Set<FileKey> preserved = new HashSet<>();
		for (Preservation preservation : plan.preservations()) preserved.add(new FileKey(preservation.root(), preservation.relativePath()));
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
				if (preserved.contains(key)) entries.add(new Entry(Kind.PRESERVED_CAS, key.root(), key.relativePath(), size));
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

		entries.sort(Comparator.comparing((Entry entry) -> entry.kind.ordinal()).thenComparing(entry -> entry.root.ordinal()).thenComparing(Entry::relativePath));
		GroupConsequences consequences = selection == null ? new GroupConsequences(Set.of(), Set.of(), Set.of()) : consequences(selection);
		return new UpdatePreview(plan, entries, consequences, patchNotes, patchNotesHistory, mode);
	}

	private static GroupConsequences consequences(ResolvedSelection selection) {
		Map<String, String> explanations = new TreeMap<>();
		selection.explanations().forEach((groupId, resolution) -> explanations.put(groupId, resolution.explanation()));
		return new GroupConsequences(selection.intent().requestedGroups(), selection.selectedGroups(), selection.staleRequestedGroups(), explanations);
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

	public record GroupConsequences(Set<String> explicitGroups, Set<String> resolvedGroups, Set<String> staleGroups, Map<String, String> explanations) {
		public GroupConsequences(Set<String> explicitGroups, Set<String> resolvedGroups, Set<String> staleGroups) {
			this(explicitGroups, resolvedGroups, staleGroups, Map.of());
		}

		public GroupConsequences {
			explicitGroups = immutable(explicitGroups);
			resolvedGroups = immutable(resolvedGroups);
			staleGroups = immutable(staleGroups);
			explanations = immutableMap(explanations);
		}

		private static Set<String> immutable(Set<String> values) {
			return Set.copyOf(new TreeSet<>(values == null ? Set.of() : values));
		}

		private static Map<String, String> immutableMap(Map<String, String> values) {
			return Map.copyOf(new TreeMap<>(values == null ? Map.of() : values));
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
		PRESERVED_CAS(SummaryBucket.PRESERVED, SortBucket.PRESERVED, "  "),
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
