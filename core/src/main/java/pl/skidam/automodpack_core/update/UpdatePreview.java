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

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationMetadata;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.update.UpdatePlan.FileKey;
import pl.skidam.automodpack_core.update.UpdatePlan.FileState;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

public record UpdatePreview(
		UpdatePlan plan,
		List<Entry> entries,
		GroupConsequences groupConsequences,
		String patchNotes) {
	public UpdatePreview {
		plan = Objects.requireNonNull(plan, "plan");
		entries = List.copyOf(entries);
		groupConsequences = Objects.requireNonNull(groupConsequences, "groupConsequences");
		patchNotes = GenerationMetadata.validateNotes(patchNotes == null ? "" : patchNotes);
	}

	public UpdatePreview(UpdatePlan plan, List<Entry> entries, GroupConsequences groupConsequences) {
		this(plan, entries, groupConsequences, "");
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
		return entries.stream().filter(entry -> entry.kind == Kind.PRESERVED_CAS || entry.kind == Kind.PRESERVED_CHANGED
				|| entry.kind == Kind.PRESERVED_UNAVAILABLE || entry.kind == Kind.PRESERVED_OUTSIDE).mapToLong(Entry::size).sum();
	}

	public long uncachedAcquisitionBytes() {
		return plan.operations().stream()
				.filter(operation -> operation.operation() == OperationType.INSTALL_OBJECT && operation.root() == Root.PROJECTION && operation.expectedExistingHash() == null)
				.mapToLong(Operation::expectedSize).sum();
	}

	public Set<RestartReason> restartReasons() {
		return plan.restartReasons();
	}

	private long bytesOf(Kind kind) {
		return entries.stream().filter(entry -> entry.kind == kind).mapToLong(Entry::size).sum();
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, Jsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal) {
		return create(plan, originalFiles, target, selection, removal, null, "");
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, Jsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal, String patchNotes) {
		return create(plan, originalFiles, target, selection, removal, null, patchNotes);
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, Jsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal, Jsons.ClientBaselineFields baseline) {
		return create(plan, originalFiles, target, selection, removal, baseline, "");
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, Jsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal, Jsons.ClientBaselineFields baseline, String patchNotes) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(originalFiles, "originalFiles");
		Objects.requireNonNull(target, "target");
		Map<FileKey, Operation> operations = plan.operations().stream()
				.collect(Collectors.toMap(operation -> new FileKey(operation.root(), operation.relativePath()), operation -> operation));
		Set<FileKey> preserved = new HashSet<>();
		for (Preservation preservation : plan.preservations()) preserved.add(new FileKey(preservation.root(), preservation.relativePath()));
		Map<String, Jsons.ClientBaselineFields.EntryFields> baselineEntries = baselineEntries(baseline);
		List<Entry> entries = new ArrayList<>();
		for (Operation operation : plan.operations()) {
			FileKey key = new FileKey(operation.root(), operation.relativePath());
			FileState previous = originalFiles.get(key);
			if (operation.operation() == OperationType.INSTALL_OBJECT) {
				Kind kind = operation.expectedExistingHash() != null
						? Kind.RESTORED_BASELINE
						: previous == null || !previous.regularFile() ? Kind.ADDED : Kind.CHANGED;
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
			if (currentHash == null || !currentHash.matches("[0-9a-fA-F]{40}") || current.size() < 0) {
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
		return new UpdatePreview(plan, entries, consequences, patchNotes);
	}

	private static GroupConsequences consequences(ResolvedSelection selection) {
		Map<String, String> explanations = new TreeMap<>();
		selection.explanations().forEach((groupId, resolution) -> explanations.put(groupId, resolution.explanation()));
		return new GroupConsequences(selection.intent().requestedTags(), selection.intent().requestedGroups(), selection.selectedGroups(), selection.staleRequestedTags(),
				selection.staleRequestedGroups(), explanations);
	}

	private static Map<String, Jsons.ClientBaselineFields.EntryFields> baselineEntries(Jsons.ClientBaselineFields baseline) {
		if (baseline == null || baseline.entries == null) return Map.of();
		Map<String, Jsons.ClientBaselineFields.EntryFields> entries = new HashMap<>();
		for (var entry : baseline.entries) {
			if (entry != null && entry.logicalPath != null) entries.put(UpdatePlanner.normalize(entry.logicalPath), entry);
		}
		return entries;
	}

	private static boolean baselineMatches(FileState current, Jsons.ClientBaselineFields.EntryFields baseline) {
		return baseline != null && !baseline.absent && baseline.objectHash != null && baseline.objectHash.matches("[0-9a-fA-F]{40}")
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

	public record GroupConsequences(Set<String> explicitTags, Set<String> explicitGroups, Set<String> resolvedGroups, Set<String> staleTags,
			Set<String> staleGroups, Map<String, String> explanations) {
		public GroupConsequences(Set<String> explicitGroups, Set<String> resolvedGroups, Set<String> staleGroups) {
			this(Set.of(), explicitGroups, resolvedGroups, Set.of(), staleGroups, Map.of());
		}

		public GroupConsequences {
			explicitTags = immutable(explicitTags);
			explicitGroups = immutable(explicitGroups);
			resolvedGroups = immutable(resolvedGroups);
			staleTags = immutable(staleTags);
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

	public enum Kind {
		ADDED,
		CHANGED,
		REMOVED,
		PRESERVED_CAS,
		PRESERVED_CHANGED,
		PRESERVED_UNAVAILABLE,
		PRESERVED_OUTSIDE,
		UNSAFE,
		RESTORED_BASELINE
	}
}
