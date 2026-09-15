package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

/**
 * The client's per-pack replica of the server head document. Like the journal mirror it is only ever replaced whole
 * with a fetched copy of the server artifact, never rewritten client-side. Its installed hash is the conditional-fetch
 * validator, and it vouches only for a generation the active state actually points at: an unapplied update never
 * counts as installed.
 */
public final class HeadMirror {
	private final ClientStorage storage;

	public HeadMirror(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage, "storage");
	}

	public boolean exists(String modpackId) {
		return Files.exists(storage.historyHeadFile(modpackId), LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * The parsed head document of the pack's mirror, or null when no mirror exists yet. Unusable content is set aside
	 * as evidence and reads as absent; physical IO of a regular file propagates. The mirror is a pure replica, so the
	 * next head fetch replaces an asided copy.
	 */
	public GenerationJsons.HeadDocumentFields read(String modpackId) throws IOException {
		Path file = storage.historyHeadFile(modpackId);
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client head mirror is not a regular file: " + file);
		try {
			return ModpackContentTools.readHeadDocument(file);
		} catch (ConfigTools.ConfigParseException e) {
			DurableFiles.setAside(file, "Client head mirror", e);
			return null;
		}
	}

	/** The sha1 of the mirror bytes only when the mirror parses and names the generation the active state points at; null otherwise. */
	public String installedSha1(ClientGenerationStore generations, String modpackId) throws IOException {
		GenerationJsons.HeadDocumentFields head = read(modpackId);
		if (head == null || head.contentToken == null || head.contentToken.isBlank()) return null;
		if (!generations.headMatchesActive(modpackId, head.contentToken)) return null;
		return HashUtils.getHash(storage.historyHeadFile(modpackId));
	}

	/** Verifies the fetched head document parses and names a pack, then swaps it in as that pack's mirror under the mutation lock. */
	public void replaceFrom(Path fetchedFile) throws IOException {
		GenerationJsons.HeadDocumentFields head = ModpackContentTools.readHeadDocument(fetchedFile);
		if (head == null) throw new IOException("Fetched head document is empty: " + fetchedFile);
		String modpackId = ModpackId.requireValid(head.policy.modpackId);
		ClientStorageMutation.run(storage, () -> {
			// Re-read under the lock so the swap publishes exactly the bytes that were verified.
			GenerationJsons.HeadDocumentFields verified = ModpackContentTools.readHeadDocument(fetchedFile);
			if (verified == null || !modpackId.equals(verified.policy.modpackId)) throw new IOException("Fetched head document changed while it was swapped into the mirror");
			Path mirror = storage.historyHeadFile(modpackId);
			Files.createDirectories(mirror.getParent());
			DurableFiles.replace(fetchedFile, mirror);
			return null;
		});
	}
}
