package pl.skidam.automodpack_core.update;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.update.UpdatePlan.FileKey;
import pl.skidam.automodpack_core.update.UpdatePlan.FileState;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

public record UpdatePreview(
		UpdatePlan plan,
		List<Entry> entries,
		GroupConsequences groupConsequences) {
	public UpdatePreview {
		plan = Objects.requireNonNull(plan, "plan");
		entries = List.copyOf(entries);
		groupConsequences = Objects.requireNonNull(groupConsequences, "groupConsequences");
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

	private long bytesOf(Kind kind) {
		return entries.stream().filter(entry -> entry.kind == kind).mapToLong(Entry::size).sum();
	}

	public static UpdatePreview create(UpdatePlan plan, Map<FileKey, FileState> originalFiles, Jsons.ModpackContentFields target,
			ResolvedSelection selection, boolean removal) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(originalFiles, "originalFiles");
		Objects.requireNonNull(target, "target");
		Map<FileKey, Operation> operations = plan.operations().stream()
				.collect(java.util.stream.Collectors.toMap(operation -> new FileKey(operation.root(), operation.relativePath()), operation -> operation));
		Set<FileKey> preserved = new java.util.HashSet<>();
		for (Preservation preservation : plan.preservations()) preserved.add(new FileKey(preservation.root(), preservation.relativePath()));
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
		if (target.list != null) for (var item : target.list) targetPaths.add(UpdatePlanner.normalize(item.file));
		OwnershipLedger ledger = OwnershipLedger.fromFields(target.ownershipLedger);
		for (OwnershipLedger.Entry ledgerEntry : ledger.entries().values()) {
			if (targetPaths.contains(ledgerEntry.logicalPath())) continue;
			java.util.Optional<FileKey> optionalKey = UpdatePlanner.managedCleanupKey(ledgerEntry.logicalPath());
			if (optionalKey.isEmpty()) continue;
			FileKey key = optionalKey.get();
			FileState current = originalFiles.get(key);
			if (current == null || operations.containsKey(key)) continue;
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
		GroupConsequences consequences = selection == null
				? new GroupConsequences(Set.of(), Set.of(), Set.of())
				: new GroupConsequences(selection.intent().requestedGroups(), selection.selectedGroups(), selection.staleRequestedGroups());
		return new UpdatePreview(plan, entries, consequences);
	}

	public record Entry(Kind kind, Root root, String relativePath, long size) {
		public Entry {
			kind = Objects.requireNonNull(kind, "kind");
			root = Objects.requireNonNull(root, "root");
			if (relativePath == null || relativePath.isBlank()) throw new IllegalArgumentException("Preview path is missing");
			if (size < 0) throw new IllegalArgumentException("Preview size is negative");
		}
	}

	public record GroupConsequences(Set<String> explicitGroups, Set<String> resolvedGroups, Set<String> staleGroups) {
		public GroupConsequences {
			explicitGroups = immutable(explicitGroups);
			resolvedGroups = immutable(resolvedGroups);
			staleGroups = immutable(staleGroups);
		}

		private static Set<String> immutable(Set<String> values) {
			return Set.copyOf(new TreeSet<>(values == null ? Set.of() : values));
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
