package pl.skidam.automodpack_core.change;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackContentType;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;

/**
 * The immutable, logical view of a set of file changes.
 *
 * <p>
 * A logical path can have more than one physical occurrence. For example, a
 * projection file can be replaced by a copy in the game directory, or the same
 * catalogue file can be supplied by more than one group. A change keeps all of
 * those occurrences instead of choosing one representative. Consumers that
 * need a compact list may still project this model, but the canonical data does
 * not lose roots, group provenance, or source references.
 * </p>
 */
public final class ChangeSet {
	private static final Comparator<Change> CHANGE_ORDER = Comparator.comparingInt((Change change) -> change.kind().sortOrder())
			.thenComparing(Change::logicalPath);
	private static final Comparator<Occurrence> OCCURRENCE_ORDER = Comparator.comparing(Occurrence::location)
			.thenComparing(Occurrence::logicalPath).thenComparingLong(Occurrence::size)
			.thenComparing(occurrence -> Objects.toString(occurrence.beforeHash(), ""))
			.thenComparing(occurrence -> Objects.toString(occurrence.afterHash(), ""))
			.thenComparing(Occurrence::contentKind)
			.thenComparing(occurrence -> String.join("\u0000", occurrence.featureIds()));

	private final List<Change> changes;
	private final List<Effect> effects;

	private ChangeSet(List<Change> changes, List<Effect> effects) {
		this.changes = List.copyOf(changes);
		this.effects = List.copyOf(effects);
	}

	public static ChangeSet empty() {
		return new ChangeSet(List.of(), List.of());
	}

	/** Creates a set and folds repeated logical paths while retaining every occurrence. */
	public static ChangeSet of(Collection<Change> changes, Collection<Effect> effects) {
		Objects.requireNonNull(changes, "changes");
		Map<String, ChangeAccumulator> grouped = new TreeMap<>();
		for (Change change : changes) {
			Objects.requireNonNull(change, "change");
			grouped.computeIfAbsent(change.logicalPath(), ignored -> new ChangeAccumulator(change.logicalPath())).add(change);
		}
		List<Change> normalized = new ArrayList<>(grouped.size());
		for (ChangeAccumulator accumulator : grouped.values()) normalized.add(accumulator.finish());
		normalized.sort(CHANGE_ORDER);
		List<Effect> normalizedEffects = new ArrayList<>();
		if (effects != null) for (Effect effect : effects) if (effect != null) normalizedEffects.add(effect);
		normalizedEffects.sort(Comparator.comparing(Effect::category).thenComparing(Effect::value));
		return new ChangeSet(normalized, deduplicate(normalizedEffects));
	}

	public static ChangeSet of(Collection<Change> changes) {
		return of(changes, List.of());
	}

	public static ChangeSet of(Change change) {
		return of(List.of(change), List.of());
	}

	/** Creates the canonical current-state view of a complete modpack catalogue. */
	public static ChangeSet catalogue(GroupManifest manifest) {
		Objects.requireNonNull(manifest, "catalogue manifest");
		List<Change> changes = new ArrayList<>();
		for (var group : manifest.groups().entrySet()) for (var file : group.getValue().files().entrySet()) {
			GroupManifest.GroupFile value = file.getValue();
			changes.add(new Change(file.getKey(), Kind.PRESERVED,
					List.of(new Occurrence("catalogue", file.getKey(), value.size(), null, value.sha1(), value.type(), List.of(group.getKey()), List.of()))));
		}
		return of(changes);
	}

	public List<Change> changes() {
		return changes;
	}

	public List<Effect> effects() {
		return effects;
	}

	/** Returns a copy with references supplied for each physical occurrence. */
	public ChangeSet withReferences(ReferenceProvider provider) {
		Objects.requireNonNull(provider, "reference provider");
		List<Change> referenced = new ArrayList<>(changes.size());
		for (Change change : changes) {
			List<Occurrence> occurrences = new ArrayList<>(change.occurrences().size());
			for (Occurrence occurrence : change.occurrences()) {
				List<String> references = new ArrayList<>(occurrence.references());
				List<String> supplied = provider.references(occurrence.location(), occurrence.logicalPath());
				if (supplied != null) for (String reference : supplied) if (reference != null && !reference.isBlank() && !references.contains(reference)) references.add(reference);
				occurrences.add(occurrence.withReferences(references));
			}
			referenced.add(new Change(change.logicalPath(), change.kind(), occurrences));
		}
		return of(referenced, effects);
	}

	public ChangeSet withEffects(Collection<Effect> additionalEffects) {
		List<Effect> combined = new ArrayList<>(effects);
		if (additionalEffects != null) for (Effect effect : additionalEffects) if (effect != null) combined.add(effect);
		return of(changes, combined);
	}

	public Summary summary() {
		int added = 0;
		int modified = 0;
		int removed = 0;
		int preserved = 0;
		int unsafe = 0;
		int metadataOnly = 0;
		for (Change change : changes) {
			switch (change.kind()) {
				case ADDED -> added++;
				case MODIFIED -> modified++;
				case REMOVED -> removed++;
				case PRESERVED, PRESERVED_CHANGED, PRESERVED_UNAVAILABLE, PRESERVED_OUTSIDE -> preserved++;
				case UNSAFE -> unsafe++;
				case METADATA_ONLY -> metadataOnly++;
			}
		}
		return new Summary(added, modified, removed, preserved, unsafe, metadataOnly, effects.size());
	}

	private static List<Effect> deduplicate(List<Effect> effects) {
		Map<String, Effect> unique = new LinkedHashMap<>();
		for (Effect effect : effects) unique.putIfAbsent(effect.category() + "\u0000" + effect.value(), effect);
		return List.copyOf(unique.values());
	}

	private static final class ChangeAccumulator {
		private final String logicalPath;
		private final List<Occurrence> occurrences = new ArrayList<>();
		private Kind kind;

		private ChangeAccumulator(String logicalPath) {
			this.logicalPath = logicalPath;
		}

		private void add(Change change) {
			kind = kind == null ? change.kind() : Kind.combine(kind, change.kind());
			occurrences.addAll(change.occurrences());
		}

		private Change finish() {
			occurrences.sort(OCCURRENCE_ORDER);
			return new Change(logicalPath, kind, occurrences);
		}
	}

	@FunctionalInterface
	public interface ReferenceProvider {
		List<String> references(String location, String logicalPath);
	}

	public enum Kind {
		UNSAFE(0),
		REMOVED(1),
		ADDED(2),
		MODIFIED(2),
		METADATA_ONLY(2),
		PRESERVED_CHANGED(3),
		PRESERVED_UNAVAILABLE(3),
		PRESERVED_OUTSIDE(3),
		PRESERVED(3);

		private final int sortOrder;

		Kind(int sortOrder) {
			this.sortOrder = sortOrder;
		}

		private int sortOrder() {
			return sortOrder;
		}

		private static Kind combine(Kind first, Kind second) {
			if (first == UNSAFE || second == UNSAFE) return UNSAFE;
			if (first == MODIFIED || second == MODIFIED) return MODIFIED;
			if (first == ADDED && second == REMOVED || first == REMOVED && second == ADDED) return MODIFIED;
			if (first == ADDED || second == ADDED) return ADDED;
			if (first == REMOVED || second == REMOVED) return REMOVED;
			if (first == METADATA_ONLY || second == METADATA_ONLY) return METADATA_ONLY;
			if (first == PRESERVED_CHANGED || second == PRESERVED_CHANGED) return PRESERVED_CHANGED;
			if (first == PRESERVED_UNAVAILABLE || second == PRESERVED_UNAVAILABLE) return PRESERVED_UNAVAILABLE;
			if (first == PRESERVED_OUTSIDE || second == PRESERVED_OUTSIDE) return PRESERVED_OUTSIDE;
			return PRESERVED;
		}
	}

	public record Change(String logicalPath, Kind kind, List<Occurrence> occurrences) {
		public Change {
			logicalPath = LogicalPath.requireCanonical(logicalPath);
			kind = Objects.requireNonNull(kind, "change kind");
			if (occurrences == null || occurrences.isEmpty()) throw new IllegalArgumentException("Change has no physical occurrences");
			List<Occurrence> normalized = new ArrayList<>(occurrences.size());
			for (Occurrence occurrence : occurrences) {
				Objects.requireNonNull(occurrence, "change occurrence");
				if (!logicalPath.equals(occurrence.logicalPath())) throw new IllegalArgumentException("Change occurrence path does not match logical path");
				normalized.add(occurrence);
			}
			occurrences = List.copyOf(normalized);
		}

		public Occurrence primaryOccurrence() {
			return occurrences.get(0);
		}
	}

	public record Occurrence(String location, String logicalPath, long size, String beforeHash, String afterHash, String contentKind, List<String> featureIds, List<String> references) {
		public Occurrence(String location, String logicalPath, long size) {
			this(location, logicalPath, size, null, null, null, List.of(), List.of());
		}

		public Occurrence(String location, String logicalPath, long size, String beforeHash, String afterHash) {
			this(location, logicalPath, size, beforeHash, afterHash, null, List.of(), List.of());
		}

		public Occurrence(String location, String logicalPath, long size, String beforeHash, String afterHash, List<String> references) {
			this(location, logicalPath, size, beforeHash, afterHash, null, List.of(), references);
		}

		public Occurrence(String location, String logicalPath, long size, String beforeHash, String afterHash, String contentKind, List<String> references) {
			this(location, logicalPath, size, beforeHash, afterHash, contentKind, List.of(), references);
		}

		public Occurrence {
			if (location == null || location.isBlank()) throw new IllegalArgumentException("Change occurrence location is missing");
			location = location.trim();
			logicalPath = LogicalPath.requireCanonical(logicalPath);
			if (size < 0) throw new IllegalArgumentException("Change occurrence size is negative");
			beforeHash = normalizeHash(beforeHash, "before hash");
			afterHash = normalizeHash(afterHash, "after hash");
			contentKind = normalizeContentKind(contentKind, logicalPath);
			featureIds = normalizedValues(featureIds);
			List<String> normalizedReferences = new ArrayList<>();
			if (references != null) for (String reference : references) if (reference != null && !reference.isBlank() && !normalizedReferences.contains(reference)) normalizedReferences.add(reference);
			references = List.copyOf(normalizedReferences);
		}

		public Occurrence withReferences(List<String> newReferences) {
			return new Occurrence(location, logicalPath, size, beforeHash, afterHash, contentKind, featureIds, newReferences);
		}
	}

	public record Effect(String category, String value) {
		public Effect {
			if (category == null || category.isBlank()) throw new IllegalArgumentException("Change effect category is missing");
			if (value == null || value.isBlank()) throw new IllegalArgumentException("Change effect value is missing");
			category = category.trim();
			value = value.trim();
		}
	}

	public record Summary(int addedFiles, int modifiedFiles, int removedFiles, int preservedFiles, int unsafeFiles, int metadataOnlyFiles, int effectCount) {}

	private static String normalizeHash(String value, String label) {
		if (value == null || value.isBlank()) return null;
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (!normalized.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid " + label);
		return normalized;
	}

	private static String normalizeContentKind(String value, String logicalPath) {
		if (value == null || value.isBlank()) return ModpackPathPolicy.isModPath(logicalPath) ? ModpackContentType.MOD : ModpackPathPolicy.typeForPath(logicalPath);
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private static List<String> normalizedValues(Collection<String> values) {
		List<String> normalized = new ArrayList<>();
		if (values != null) for (String value : values) if (value != null && !value.isBlank() && !normalized.contains(value.trim())) normalized.add(value.trim());
		normalized.sort(String::compareTo);
		return List.copyOf(normalized);
	}
}
