package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.Journal;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The client's per-pack replica of the server journal file. The mirror is only ever replaced whole
 * with a fetched copy of the server artifact, never rewritten or trimmed client-side.
 */
public final class JournalMirror {
	private final ClientStorage storage;

	/*
	 * The mirror is replaced whole by an atomic rename on every sync, so parsed entries stay valid while the file's
	 * (mtime, size) is unchanged. Joins and sweeps parse the same mirror several times per pass; the memo turns every
	 * repeat into a stat. An asided copy is never memoized - the aside changes the file underneath.
	 */
	private static final Map<Path, VerifiedMirror> VERIFIED_MIRRORS = new ConcurrentHashMap<>();

	private record VerifiedMirror(FileTime modified, long size, List<JournalEntry> entries) {}

	public JournalMirror(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage, "storage");
	}

	/**
	 * Every journal entry of the pack's mirror, or an empty list when no mirror exists yet. Unusable journal content
	 * is set aside as evidence and reads as empty; physical IO of a regular file propagates. The mirror is a pure
	 * replica, so the next head fetch replaces an asided copy.
	 */
	public List<JournalEntry> entries(String modpackId) throws IOException {
		Path file = storage.historyJournalFile(modpackId);
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client journal mirror is not a regular file: " + file);
		BasicFileAttributes attributes = Files.getFileAttributeView(file, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
		VerifiedMirror memo = VERIFIED_MIRRORS.get(file);
		if (memo != null && memo.modified().equals(attributes.lastModifiedTime()) && memo.size() == attributes.size()) return memo.entries();
		try {
			List<JournalEntry> entries = Journal.openComplete(file).entries();
			VERIFIED_MIRRORS.put(file, new VerifiedMirror(attributes.lastModifiedTime(), attributes.size(), entries));
			return entries;
		} catch (Journal.UnusableContentException e) {
			DurableFiles.setAside(file, "Client journal mirror", e);
			return List.of();
		}
	}

	/** The content token of the mirror's last entry, or empty when the mirror is missing or empty. */
	public Optional<String> lastEntryToken(String modpackId) throws IOException {
		List<JournalEntry> entries = entries(modpackId);
		return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1).contentToken());
	}

	public boolean exists(String modpackId) {
		return Files.exists(storage.historyJournalFile(modpackId), LinkOption.NOFOLLOW_LINKS);
	}

	/** Verifies the fetched journal file parses completely, then swaps it in as the pack's mirror under the mutation lock. */
	public void replaceFrom(String modpackId, Path fetchedFile) throws IOException {
		ModpackId.requireValid(modpackId);
		FileCache.StatSnapshot beforeParse = FileCache.statSnapshot(fetchedFile);
		if (!beforeParse.isTrackedRegularFile()) throw new IOException("Fetched journal is not a regular file: " + fetchedFile);
		List<JournalEntry> entries = Journal.openComplete(fetchedFile).entries();
		FileCache.StatSnapshot afterParse = FileCache.statSnapshot(fetchedFile);
		if (!sameContentStat(beforeParse, afterParse)) throw new IOException("Fetched journal changed while it was parsed");
		ClientStorageMutation.run(storage, () -> {
			replaceFromLocked(modpackId, entries, fetchedFile, afterParse);
			return null;
		});
	}

	/**
	 * Whether the file's content could have changed between two stats: its identity, size or modification time moved.
	 * The change time is left out because antivirus scanners and sync clients touch it on a fresh file without writing.
	 */
	private static boolean sameContentStat(FileCache.StatSnapshot a, FileCache.StatSnapshot b) {
		FileCache.FileFingerprint x = a.fingerprint(), y = b.fingerprint();
		return x.size() == y.size() && x.lastModifiedNanos() == y.lastModifiedNanos() && java.util.Objects.equals(x.fileKey(), y.fileKey());
	}

	private void replaceFromLocked(String modpackId, List<JournalEntry> entries, Path fetchedFile, FileCache.StatSnapshot parsed) throws IOException {
		if (!sameContentStat(parsed, FileCache.statSnapshot(fetchedFile))) throw new IOException("Fetched journal changed while it was swapped into the mirror");
		Path mirror = storage.historyJournalFile(modpackId);
		Files.createDirectories(mirror.getParent());
		DurableFiles.replace(fetchedFile, mirror);
		BasicFileAttributes attributes = Files.getFileAttributeView(mirror, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
		VERIFIED_MIRRORS.put(mirror, new VerifiedMirror(attributes.lastModifiedTime(), attributes.size(), entries));
	}
}
