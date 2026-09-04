package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.Journal;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;

/**
 * The client's per-pack replica of the server journal file. The mirror is only ever replaced whole
 * with a fetched copy of the server artifact, never rewritten or trimmed client-side.
 */
public final class JournalMirror {
	private final ClientStorage storage;

	public JournalMirror(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage, "storage");
	}

	/** Every journal entry of the pack's mirror, or an empty list when no mirror exists yet. */
	public List<JournalEntry> entries(String modpackId) throws IOException {
		Path file = storage.historyJournalFile(modpackId);
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client journal mirror is not a regular file: " + file);
		return Journal.open(file).entries();
	}

	/** The content token of the mirror's last entry, or empty when the mirror is missing or empty. */
	public Optional<String> lastEntryToken(String modpackId) throws IOException {
		List<JournalEntry> entries = entries(modpackId);
		return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1).contentToken());
	}

	public boolean exists(String modpackId) {
		return Files.exists(storage.historyJournalFile(modpackId), LinkOption.NOFOLLOW_LINKS);
	}

	/** True when the mirror cannot vouch for the head document: missing, unreadable, or its last entry carries another token. */
	public boolean isStale(String modpackId, String headContentToken) {
		try {
			Optional<String> last = lastEntryToken(modpackId);
			return last.isEmpty() || !last.get().equals(headContentToken);
		} catch (IOException | RuntimeException e) {
			return true;
		}
	}

	/** Verifies the fetched journal file parses completely, then swaps it in as the pack's mirror under the mutation lock. */
	public void replaceFrom(String modpackId, Path fetchedFile) throws IOException {
		ModpackId.requireValid(modpackId);
		List<JournalEntry> entries = Journal.open(fetchedFile).entries();
		ClientStorageMutation.run(storage, () -> {
			replaceFromLocked(modpackId, entries.size(), fetchedFile);
			return null;
		});
	}

	private void replaceFromLocked(String modpackId, int verifiedEntries, Path fetchedFile) throws IOException {
		if (Journal.open(fetchedFile).length() != verifiedEntries) throw new IOException("Fetched journal changed while it was swapped into the mirror");
		Path mirror = storage.historyJournalFile(modpackId);
		Files.createDirectories(mirror.getParent());
		Files.move(fetchedFile, mirror, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}
}
