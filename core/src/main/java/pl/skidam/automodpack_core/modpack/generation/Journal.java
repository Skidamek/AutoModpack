package pl.skidam.automodpack_core.modpack.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
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
