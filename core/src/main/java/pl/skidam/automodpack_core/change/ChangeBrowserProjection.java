package pl.skidam.automodpack_core.change;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;

/**
 * Pure projections of a canonical {@link ChangeSet} for file browsers.
 *
 * <p>
 * The projection applies the same query to both modes. A list contains the
 * matching files, while a tree inserts only the directory ancestors needed
 * to reach those files. A tree folder's aggregate counts its descendant
 * logical files once, even when a file has more than one physical occurrence.
 * The source decides whether a change is a current catalogue entry or a diff;
 * this module does not branch on that workflow distinction.
 * </p>
 */
public final class ChangeBrowserProjection {
	private static final Comparator<Map.Entry<String, TreeNode>> TREE_ORDER = Comparator
			.comparing((Map.Entry<String, TreeNode> entry) -> entry.getValue().isDirectory() ? 0 : 1)
			.thenComparing(Map.Entry::getKey);

	private ChangeBrowserProjection() {}

	public static Projection project(ChangeSet changes, Mode mode) {
		return project(changes, mode, Filter.all());
	}

	public static Projection project(ChangeSet changes, Mode mode, Filter filter) {
		Objects.requireNonNull(changes, "changes");
		Objects.requireNonNull(mode, "projection mode");
		Objects.requireNonNull(filter, "change filter");
		List<FileRow> files = visibleFiles(changes, filter);
		Aggregate total = aggregate(files);
		if (mode == Mode.LIST) return new Projection(mode, files.stream().map(file -> file.withDepth(0)).map(Row.class::cast).toList(), total);
		TreeNode root = new TreeNode("");
		for (FileRow file : files) root.add(file);
		aggregateNode(root);
		List<Row> rows = new ArrayList<>();
		appendTreeRows(root, 0, rows);
		return new Projection(mode, rows, total);
	}

	private static List<FileRow> visibleFiles(ChangeSet changes, Filter filter) {
		List<FileRow> visible = new ArrayList<>();
		for (ChangeSet.Change change : changes.changes()) {
			List<ChangeSet.Occurrence> occurrences = filter.visibleOccurrences(change);
			if (occurrences.isEmpty()) continue;
			visible.add(new FileRow(change.logicalPath(), 0, change.kind(), occurrences));
		}
		return List.copyOf(visible);
	}

	private static void appendTreeRows(TreeNode node, int depth, List<Row> rows) {
		for (Map.Entry<String, TreeNode> entry : node.children.entrySet().stream().sorted(TREE_ORDER).toList()) {
			TreeNode child = entry.getValue();
			if (child.file != null) rows.add(child.file.withDepth(depth));
			if (!child.children.isEmpty()) {
				rows.add(new FolderRow(child.path, depth, aggregateChildren(child)));
				appendTreeRows(child, depth + 1, rows);
			}
		}
	}

	private static Aggregate aggregateNode(TreeNode node) {
		Aggregate aggregate = node.file == null ? Aggregate.empty() : node.file.aggregate();
		for (TreeNode child : node.children.values()) aggregate = aggregate.merge(aggregateNode(child));
		node.aggregate = aggregate;
		return aggregate;
	}

	private static Aggregate aggregateChildren(TreeNode node) {
		Aggregate aggregate = Aggregate.empty();
		for (TreeNode child : node.children.values()) aggregate = aggregate.merge(child.aggregate);
		return aggregate;
	}

	private static Aggregate aggregate(Collection<FileRow> files) {
		Aggregate aggregate = Aggregate.empty();
		for (FileRow file : files) aggregate = aggregate.merge(file.aggregate());
		return aggregate;
	}

	public enum Mode {
		TREE,
		LIST
	}

	/** A query shared by tree and list projections. Empty sets mean no restriction. */
	public record Filter(String search, Set<String> contentKinds, Set<String> featureIds) {
		public Filter {
			search = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
			contentKinds = normalizedKinds(contentKinds);
			featureIds = normalizedFeatures(featureIds);
		}

		public static Filter all() {
			return new Filter("", Set.of(), Set.of());
		}

		public Filter withSearch(String value) {
			return new Filter(value, contentKinds, featureIds);
		}

		public Filter withContentKinds(Collection<String> values) {
			return new Filter(search, normalizedKinds(values), featureIds);
		}

		public Filter withFeatures(Collection<String> values) {
			return new Filter(search, contentKinds, normalizedFeatures(values));
		}

		private List<ChangeSet.Occurrence> visibleOccurrences(ChangeSet.Change change) {
			List<ChangeSet.Occurrence> filtered = change.occurrences().stream().filter(this::matchesOccurrence).toList();
			if (filtered.isEmpty()) return List.of();
			if (search.isBlank() || contains(search, change.logicalPath())) return filtered;
			return filtered.stream().filter(this::matchesSearch).toList();
		}

		private boolean matchesOccurrence(ChangeSet.Occurrence occurrence) {
			return (contentKinds.isEmpty() || contentKinds.contains(occurrence.contentKind()))
					&& (featureIds.isEmpty() || occurrence.featureIds().stream().anyMatch(featureIds::contains));
		}

		private boolean matchesSearch(ChangeSet.Occurrence occurrence) {
			return contains(search, occurrence.location()) || contains(search, occurrence.contentKind()) || occurrence.featureIds().stream().anyMatch(value -> contains(search, value));
		}

		private static boolean contains(String query, String value) {
			return value != null && value.toLowerCase(Locale.ROOT).contains(query);
		}

		private static Set<String> normalizedKinds(Collection<String> values) {
			TreeSet<String> normalized = new TreeSet<>();
			if (values != null) for (String value : values) if (value != null && !value.isBlank()) normalized.add(value.trim().toLowerCase(Locale.ROOT));
			return Collections.unmodifiableSet(normalized);
		}

		private static Set<String> normalizedFeatures(Collection<String> values) {
			TreeSet<String> normalized = new TreeSet<>();
			if (values != null) for (String value : values) if (value != null && !value.isBlank()) normalized.add(value.trim());
			return Collections.unmodifiableSet(normalized);
		}
	}

	public record Projection(Mode mode, List<Row> rows, Aggregate total) {
		public Projection {
			mode = Objects.requireNonNull(mode, "projection mode");
			rows = List.copyOf(rows);
			total = Objects.requireNonNull(total, "projection total");
		}

		public List<FileRow> files() {
			return rows.stream().filter(FileRow.class::isInstance).map(FileRow.class::cast).toList();
		}

		public List<FolderRow> folders() {
			return rows.stream().filter(FolderRow.class::isInstance).map(FolderRow.class::cast).toList();
		}

		/** Returns this tree with descendants of the supplied folders hidden; totals remain the full query totals. */
		public Projection collapse(Collection<String> collapsedFolders) {
			if (mode != Mode.TREE || collapsedFolders == null || collapsedFolders.isEmpty()) return this;
			Set<String> normalized = new TreeSet<>();
			for (String folder : collapsedFolders) if (folder != null && !folder.isBlank()) normalized.add(LogicalPath.requireCanonical(folder.trim()));
			if (normalized.isEmpty()) return this;
			List<Row> visible = rows.stream().filter(row -> normalized.stream().noneMatch(folder -> !row.path().equals(folder) && row.path().startsWith(folder + "/"))).toList();
			return new Projection(mode, visible, total);
		}
	}

	public sealed interface Row permits FileRow, FolderRow {
		String path();

		int depth();

		Aggregate aggregate();
	}

	public record FileRow(String path, int depth, ChangeSet.Kind kind, List<ChangeSet.Occurrence> occurrences) implements Row {
		public FileRow {
			path = LogicalPath.requireCanonical(path);
			if (depth < 0) throw new IllegalArgumentException("File row depth is negative");
			kind = Objects.requireNonNull(kind, "file row change kind");
			if (occurrences == null || occurrences.isEmpty()) throw new IllegalArgumentException("File row has no occurrences");
			List<ChangeSet.Occurrence> normalized = new ArrayList<>(occurrences.size());
			for (ChangeSet.Occurrence occurrence : occurrences) {
				Objects.requireNonNull(occurrence, "file row occurrence");
				if (!path.equals(occurrence.logicalPath())) throw new IllegalArgumentException("File row occurrence path does not match row path");
				normalized.add(occurrence);
			}
			occurrences = List.copyOf(normalized);
		}

		@Override
		public Aggregate aggregate() {
			return Aggregate.single(kind, size());
		}

		public long size() {
			long size = 0;
			for (ChangeSet.Occurrence occurrence : occurrences) size = Math.max(size, occurrence.size());
			return size;
		}

		public Set<String> contentKinds() {
			TreeSet<String> kinds = new TreeSet<>();
			for (ChangeSet.Occurrence occurrence : occurrences) kinds.add(occurrence.contentKind());
			return Collections.unmodifiableSet(kinds);
		}

		public Set<String> features() {
			TreeSet<String> features = new TreeSet<>();
			for (ChangeSet.Occurrence occurrence : occurrences) features.addAll(occurrence.featureIds());
			return Collections.unmodifiableSet(features);
		}

		private FileRow withDepth(int value) {
			return new FileRow(path, value, kind, occurrences);
		}
	}

	public record FolderRow(String path, int depth, Aggregate aggregate) implements Row {
		public FolderRow {
			path = LogicalPath.requireCanonical(path);
			if (depth < 0) throw new IllegalArgumentException("Folder row depth is negative");
			aggregate = Objects.requireNonNull(aggregate, "folder aggregate");
		}
	}

	public record Aggregate(long fileCount, long byteCount, Map<ChangeSet.Kind, KindAggregate> byKind) {
		public Aggregate {
			if (fileCount < 0 || byteCount < 0) throw new IllegalArgumentException("Aggregate values are negative");
			byKind = immutableKindMap(byKind);
		}

		public static Aggregate empty() {
			return new Aggregate(0, 0, Map.of());
		}

		private static Aggregate single(ChangeSet.Kind kind, long size) {
			return new Aggregate(1, size, Map.of(kind, new KindAggregate(1, size)));
		}

		public KindAggregate forKind(ChangeSet.Kind kind) {
			return byKind.getOrDefault(kind, KindAggregate.EMPTY);
		}

		private Aggregate merge(Aggregate other) {
			EnumMap<ChangeSet.Kind, KindAggregate> merged = new EnumMap<>(ChangeSet.Kind.class);
			merged.putAll(byKind);
			for (Map.Entry<ChangeSet.Kind, KindAggregate> entry : other.byKind.entrySet()) merged.merge(entry.getKey(), entry.getValue(), KindAggregate::merge);
			return new Aggregate(Math.addExact(fileCount, other.fileCount), Math.addExact(byteCount, other.byteCount), merged);
		}

		private static Map<ChangeSet.Kind, KindAggregate> immutableKindMap(Map<ChangeSet.Kind, KindAggregate> values) {
			EnumMap<ChangeSet.Kind, KindAggregate> normalized = new EnumMap<>(ChangeSet.Kind.class);
			if (values != null) for (Map.Entry<ChangeSet.Kind, KindAggregate> entry : values.entrySet()) {
				ChangeSet.Kind kind = Objects.requireNonNull(entry.getKey(), "aggregate change kind");
				KindAggregate aggregate = Objects.requireNonNull(entry.getValue(), "aggregate kind totals");
				if (aggregate.fileCount() == 0) continue;
				normalized.put(kind, aggregate);
			}
			return Collections.unmodifiableMap(normalized);
		}
	}

	public record KindAggregate(long fileCount, long byteCount) {
		private static final KindAggregate EMPTY = new KindAggregate(0, 0);

		public KindAggregate {
			if (fileCount < 0 || byteCount < 0) throw new IllegalArgumentException("Kind aggregate values are negative");
		}

		private static KindAggregate merge(KindAggregate first, KindAggregate second) {
			return new KindAggregate(Math.addExact(first.fileCount, second.fileCount), Math.addExact(first.byteCount, second.byteCount));
		}
	}

	private static final class TreeNode {
		private final String path;
		private final Map<String, TreeNode> children = new TreeMap<>();
		private FileRow file;
		private Aggregate aggregate = Aggregate.empty();

		private TreeNode(String path) {
			this.path = path;
		}

		private boolean isDirectory() {
			return !children.isEmpty();
		}

		private void add(FileRow value) {
			String[] parts = value.path().split("/");
			TreeNode current = this;
			StringBuilder pathBuilder = new StringBuilder();
			for (int index = 0; index < parts.length; index++) {
				if (index > 0) pathBuilder.append('/');
				pathBuilder.append(parts[index]);
				current = current.children.computeIfAbsent(parts[index], ignored -> new TreeNode(pathBuilder.toString()));
			}
			current.file = value;
		}
	}
}
