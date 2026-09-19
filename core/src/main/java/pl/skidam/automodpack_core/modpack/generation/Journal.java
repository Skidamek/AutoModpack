package pl.skidam.automodpack_core.modpack.generation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.utils.JsonLines;

/** The append-only history of one modpack lineage: one entry per content change. */
public final class Journal {
	/** Unusable journal *content*, not a locked or missing file: callers may aside the evidence. Physical IO stays a plain {@link IOException}. */
	public static final class UnusableContentException extends IOException {
		public UnusableContentException(String message, Throwable cause) {
			super(message, cause);
		}

		public UnusableContentException(String message) {
			super(message);
		}
	}

	private final Path file;
	private List<JournalEntry> entries;

	private Journal(Path file, List<JournalEntry> entries) {
		this.file = file;
		this.entries = entries;
	}

	/** Opens the local durable journal, repairing away a torn final line left by a crash or power cut mid-append. */
	public static Journal open(Path file) throws IOException {
		return new Journal(file, parse(file, true));
	}

	/** Parses every line; verifying fetched journal artifacts, where a torn tail means a bad transfer instead of a crash. */
	public static Journal openComplete(Path file) throws IOException {
		return new Journal(file, parse(file, false));
	}

	private static List<JournalEntry> parse(Path file, boolean tolerateTornTail) throws IOException {
		try {
			return JsonLines.read(file, "journal", line -> JournalEntry.fromFields(ConfigTools.COMPACT.fromJson(line, GenerationJsons.JournalEntryFields.class)), tolerateTornTail);
		} catch (JsonLines.UnusableContentException e) {
			throw new UnusableContentException(e.getMessage(), e);
		}
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
		// The publication order is journal first, projection second: the appended entry must reach stable storage
		// before the projection that names it is forced, or a power cut rolls the published head back one generation.
		JsonLines.appendLine(file, ConfigTools.COMPACT.toJson(entry.toFields()));
		List<JournalEntry> updated = new ArrayList<>(entries);
		updated.add(entry);
		entries = List.copyOf(updated);
		return entry;
	}

	/** Rebuilds the served file set as of the given entry by folding the changes of every entry up to it. */
	public ContentTree treeAt(long seq) throws IOException {
		JournalEntry target = entryAt(seq);
		NavigableMap<String, ContentTree.ContentFile> files = new TreeMap<>();
		for (JournalEntry entry : entries) {
			for (JournalEntry.Change change : entry.changes()) {
				if (change.toSha1() == null) files.remove(change.path());
				else files.put(change.path(), new ContentTree.ContentFile(change.toSha1(), change.toSize()));
			}
			if (entry.seq() == seq) break;
		}
		ContentTree tree = new ContentTree(files);
		String token = tree.token();
		if (!token.equals(target.contentToken()))
			throw new UnusableContentException("Journal replay at " + seq + " produced token " + token + " but the entry recorded " + target.contentToken());
		return tree;
	}
}
