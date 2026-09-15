package pl.skidam.automodpack_core.modpack.generation;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.channels.FileChannel;
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
import com.google.gson.JsonParseException;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;

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

	private static final Gson COMPACT = ConfigTools.strictEnums(new GsonBuilder().disableHtmlEscaping()).create();

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
		if (!Files.exists(file)) return List.of();
		byte[] bytes = Files.readAllBytes(file);
		List<JournalEntry> entries = new ArrayList<>();
		int intactBytes = bytes.length;
		int lineStart = 0;
		while (lineStart < bytes.length) {
			int lineEnd = lineStart;
			while (lineEnd < bytes.length && bytes[lineEnd] != '\n') lineEnd++;
			boolean finalLine = lineEnd == bytes.length;
			String line = new String(bytes, lineStart, lineEnd - lineStart, StandardCharsets.UTF_8);
			int droppedFrom = lineStart;
			lineStart = finalLine ? bytes.length : lineEnd + 1;
			if (line.isBlank()) continue;
			try {
				entries.add(JournalEntry.fromFields(COMPACT.fromJson(line, GenerationJsons.JournalEntryFields.class)));
			} catch (IllegalArgumentException e) {
				// A line that parses as JSON but breaks the entry contract is real corruption, never a torn write - even at the tail.
				throw new UnusableContentException("Corrupt journal line " + (entries.size() + 1) + " in " + file, e);
			} catch (JsonParseException e) {
				// A crash or power cut mid-append tears the last line; anything unparsable earlier is real corruption.
				if (!finalLine || !tolerateTornTail) throw new UnusableContentException("Malformed journal line " + (entries.size() + 1) + " in " + file, e);
				LOGGER.warn("Journal {} ends in a torn line after {} intact entries; dropping the last {} bytes and keeping the intact prefix", file, entries.size(),
						bytes.length - droppedFrom);
				truncate(file, droppedFrom);
				intactBytes = droppedFrom;
				break;
			}
		}
		if (tolerateTornTail && intactBytes > 0 && bytes[intactBytes - 1] != '\n') {
			// A crash can land after the entry's bytes but before its newline; the entry parsed fine, so restore the newline before the next append fuses two entries into one line.
			Files.writeString(file, "\n", StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
		}
		return List.copyOf(entries);
	}

	/** Repairs the torn tail away, so later appends and the served file start from the intact prefix. */
	private static void truncate(Path file, long intactBytes) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.truncate(intactBytes);
			channel.force(true);
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
		String line = COMPACT.toJson(entry.toFields());
		Files.createDirectories(file.getParent());
		Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		// The publication order is journal first, projection second: the appended entry must reach stable storage
		// before the projection that names it is forced, or a power cut rolls the published head back one generation.
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
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
