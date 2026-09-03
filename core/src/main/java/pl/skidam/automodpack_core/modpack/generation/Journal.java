package pl.skidam.automodpack_core.modpack.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pl.skidam.automodpack_core.config.GenerationJsons;


/** The append-only history of one modpack lineage: one entry per content change. */
public final class Journal {
	private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

	private final Path file;
	private List<JournalEntry> entries;

	private Journal(Path file, List<JournalEntry> entries) {
		this.file = file;
		this.entries = entries;
	}

	public static Journal open(Path file) throws IOException {
		List<JournalEntry> entries = new ArrayList<>();
		if (Files.exists(file)) {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank()) continue;
				entries.add(JournalEntry.fromFields(COMPACT.fromJson(line, GenerationJsons.JournalEntryFields.class)));
			}
		}
		return new Journal(file, List.copyOf(entries));
	}

	public List<JournalEntry> entries() {
		return entries;
	}

	public int length() {
		return entries.size();
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public JournalEntry head() {
		if (entries.isEmpty()) throw new IllegalStateException("The journal is empty");
		return entries.get(entries.size() - 1);
	}

	public JournalEntry entryAt(long seq) {
		return entries.stream().filter(entry -> entry.seq() == seq).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No journal entry " + seq));
	}

	public synchronized JournalEntry append(JournalEntry entry) throws IOException {
		Objects.requireNonNull(entry, "entry");
		long expected = entries.isEmpty() ? 1 : entries.get(entries.size() - 1).seq() + 1;
		if (entry.seq() != expected) throw new IOException("Journal entry " + entry.seq() + " does not follow " + (expected - 1));
		String line = COMPACT.toJson(entry.toFields());
		Files.createDirectories(file.getParent());
		Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		List<JournalEntry> updated = new ArrayList<>(entries);
		updated.add(entry);
		entries = List.copyOf(updated);
		return entry;
	}

	/** Rewrites the journal with a single snapshot entry for the boundary state followed by every later entry. */
	public synchronized CompactionResult compact(long boundarySeq) throws IOException {
		JournalEntry boundary = entryAt(boundarySeq);
		int index = entries.indexOf(boundary);
		List<JournalEntry> retained = new ArrayList<>(entries.subList(index, entries.size()));
		int removed = entries.size() - retained.size();
		if (removed == 0) return new CompactionResult(0, entries.size(), entries.size());

		ContentTree boundaryTree = treeAt(boundarySeq);
		List<JournalEntry.Change> snapshotChanges = new ArrayList<>();
		for (var file : boundaryTree.files().entrySet()) snapshotChanges.add(JournalEntry.Change.added(file.getKey(), file.getValue().sha1(), file.getValue().size()));
		JournalEntry snapshot = new JournalEntry(1, boundary.contentToken(), boundary.policySha1(), boundary.createdAt(), boundary.notes(), boundary.restoreOf(), true, snapshotChanges);
		List<JournalEntry> rewritten = new ArrayList<>();
		rewritten.add(snapshot);
		for (int i = 1; i < retained.size(); i++) rewritten.add(retained.get(i).withSeq(i + 1));

		Path temporary = file.resolveSibling(file.getFileName() + ".compact");
		Files.createDirectories(file.getParent());
		try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			for (JournalEntry entry : rewritten) {
				writer.write(COMPACT.toJson(entry.toFields()));
				writer.write("\n");
			}
		}
		Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		int before = entries.size();
		entries = List.copyOf(rewritten);
		return new CompactionResult(removed, before, entries.size());
	}

	public record CompactionResult(long removedEntries, long entriesBefore, long entriesAfter) {}

	/** Rebuilds the served file set as of the given entry by folding changes from the last snapshot. */
	public ContentTree treeAt(long seq) {
		JournalEntry target = entryAt(seq);
		ContentTree tree = ContentTree.empty();
		for (JournalEntry entry : entries) {
			if (!entry.snapshot()) {
				for (JournalEntry.Change change : entry.changes()) tree = apply(tree, change);
			} else {
				tree = ContentTree.empty();
				for (JournalEntry.Change change : entry.changes()) {
					if (change.toSha1() == null) throw new IllegalStateException("Snapshot entry " + entry.seq() + " removes a path");
					tree = apply(tree, change);
				}
			}
			if (entry.seq() == seq) break;
		}
		String token = tree.token();
		if (!token.equals(target.contentToken()))
			throw new IllegalStateException("Journal replay at " + seq + " produced token " + token + " but the entry recorded " + target.contentToken());
		return tree;
	}

	private static ContentTree apply(ContentTree tree, JournalEntry.Change change) {
		NavigableMap<String, ContentTree.ContentFile> files = new TreeMap<>(tree.files());
		if (change.toSha1() == null) files.remove(change.path());
		else files.put(change.path(), new ContentTree.ContentFile(change.toSha1(), change.toSize()));
		return new ContentTree(files);
	}
}
