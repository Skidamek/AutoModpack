package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.ConflictAction;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

/** Durable per-modpack storage for local files displaced by an ownership conflict. */
public final class QuarantineArchive {
	private static final Comparator<Jsons.ClientQuarantineFields.EntryFields> ENTRY_ORDER = Comparator.comparing(entry -> entry.conflictId);

	private QuarantineArchive() {}

	public static void archive(ClientStorage storage, String generationId, Conflict conflict) throws IOException {
		Jsons.ClientQuarantineFields archive = read(storage, conflict.modpackId());
		Jsons.ClientQuarantineFields.EntryFields existing = archive.entries.stream().filter(entry -> conflict.conflictId().equals(entry.conflictId)).findFirst().orElse(null);
		Path payload = storage.quarantinePayload(conflict.modpackId(), conflict.conflictId());
		if (existing != null) {
			validateEntry(storage, conflict.modpackId(), existing);
			if (!same(existing, conflict)) throw new IOException("Quarantine conflict metadata disagrees with the transaction: " + conflict.conflictId());
			removeSourceIfPresent(storage, conflict, payload);
			return;
		}

		Path source = storage.gamePath(conflict.sourcePath());
		if (!SmartFileUtils.isValidFile(source, conflict.sourceSize(), conflict.sourceHash()))
			throw new IOException("Quarantine source changed after planning: " + source);
		validateNoSymbolicLinkDescendants(storage.quarantinePackDirectory(conflict.modpackId()), payload);
		Files.createDirectories(payload.getParent());
		SmartFileUtils.copyVerifiedAtomic(source, payload, conflict.sourceSize(), conflict.sourceHash());
		Jsons.ClientQuarantineFields.EntryFields entry = toFields(storage, generationId, conflict);
		archive.entries = new ArrayList<>(archive.entries);
		archive.entries.add(entry);
		archive.entries.sort(ENTRY_ORDER);
		write(storage, conflict.modpackId(), archive);
		removeSourceIfPresent(storage, conflict, payload);
	}

	public static Jsons.ClientQuarantineFields read(ClientStorage storage, String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		Path root = storage.quarantinePackDirectory(normalizedModpackId);
		if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
			Jsons.ClientQuarantineFields empty = new Jsons.ClientQuarantineFields();
			empty.modpackId = normalizedModpackId;
			empty.entries = new ArrayList<>();
			return empty;
		}
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client quarantine root is invalid: " + root);
		Path manifest = storage.quarantineManifest(normalizedModpackId);
		if (Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client quarantine manifest is missing: " + manifest);
		if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client quarantine manifest is invalid: " + manifest);
		Jsons.ClientQuarantineFields archive;
		try {
			archive = ConfigTools.read(manifest, Jsons.ClientQuarantineFields.class).orElseThrow(() -> new IOException("Client quarantine manifest is empty"));
		} catch (RuntimeException e) {
			throw new IOException("Client quarantine manifest is invalid", e);
		}
		if (archive.schemaVersion != 1 || !normalizedModpackId.equals(archive.modpackId) || archive.entries == null)
			throw new IOException("Client quarantine manifest identity is invalid");
		Set<String> ids = new HashSet<>();
		for (Jsons.ClientQuarantineFields.EntryFields entry : archive.entries) {
			if (entry == null || !ids.add(entry.conflictId)) throw new IOException("Client quarantine contains duplicate or incomplete entries");
			validateEntry(storage, normalizedModpackId, entry);
		}
		List<Jsons.ClientQuarantineFields.EntryFields> sorted = new ArrayList<>(archive.entries);
		sorted.sort(ENTRY_ORDER);
		if (!sorted.equals(archive.entries)) throw new IOException("Client quarantine entries are not ordered");
		return archive;
	}

	private static void write(ClientStorage storage, String modpackId, Jsons.ClientQuarantineFields archive) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		archive.schemaVersion = 1;
		archive.modpackId = normalizedModpackId;
		Files.createDirectories(storage.quarantinePackDirectory(normalizedModpackId));
		ConfigTools.writeAtomic(storage.quarantineManifest(normalizedModpackId), archive);
		read(storage, normalizedModpackId);
	}

	private static Jsons.ClientQuarantineFields.EntryFields toFields(ClientStorage storage, String generationId, Conflict conflict) {
		Jsons.ClientQuarantineFields.EntryFields entry = new Jsons.ClientQuarantineFields.EntryFields();
		entry.conflictId = conflict.conflictId();
		entry.action = conflict.action().name();
		entry.modIds = conflict.modIds();
		entry.sourcePath = conflict.sourcePath();
		entry.sourceHash = conflict.sourceHash().toLowerCase(Locale.ROOT);
		entry.sourceSize = conflict.sourceSize();
		entry.targetPath = conflict.targetPath();
		entry.targetHash = conflict.targetHash().toLowerCase(Locale.ROOT);
		entry.targetSize = conflict.targetSize();
		entry.quarantinePath = UpdatePlanner.normalize(storage.quarantinePackDirectory(conflict.modpackId()).relativize(storage.quarantinePayload(conflict.modpackId(), conflict.conflictId())).toString());
		entry.sourceGenerationId = generationId == null ? "" : generationId;
		entry.quarantinedAt = Instant.now().toString();
		return entry;
	}

	private static void validateEntry(ClientStorage storage, String modpackId, Jsons.ClientQuarantineFields.EntryFields entry) throws IOException {
		if (entry.conflictId == null || !entry.conflictId.matches("[0-9a-f]{40}") || entry.action == null) throw new IOException("Client quarantine entry identity is invalid");
		ConflictAction action;
		try {
			action = ConflictAction.valueOf(entry.action);
		} catch (RuntimeException e) {
			throw new IOException("Client quarantine action is invalid", e);
		}
		if (entry.modIds == null || entry.modIds.isEmpty() || entry.modIds.stream().anyMatch(value -> value == null || value.isBlank())
				|| !new TreeSet<>(entry.modIds).equals(new TreeSet<>(entry.modIds.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList())))
			throw new IOException("Client quarantine mod IDs are not canonical");
		if (entry.sourcePath == null || !UpdatePlanner.normalize(entry.sourcePath).equals(entry.sourcePath) || entry.targetPath == null || !UpdatePlanner.normalize(entry.targetPath).equals(entry.targetPath))
			throw new IOException("Client quarantine paths are invalid");
		if (!entry.sourcePath.startsWith("mods/") || !entry.targetPath.startsWith("mods/")) throw new IOException("Client quarantine path is outside the mods directory");
		validateHash(entry.sourceHash, "quarantine source hash");
		validateHash(entry.targetHash, "quarantine target hash");
		if (entry.sourceSize < 0 || entry.targetSize < 0 || entry.sourceGenerationId == null || (!entry.sourceGenerationId.isEmpty() && !entry.sourceGenerationId.matches("[0-9a-f]{40}")))
			throw new IOException("Client quarantine content metadata is invalid");
		try {
			if (entry.quarantinedAt == null || !Instant.parse(entry.quarantinedAt).toString().equals(entry.quarantinedAt)) throw new IOException("Client quarantine timestamp is invalid");
		} catch (RuntimeException e) {
			throw new IOException("Client quarantine timestamp is invalid", e);
		}
		Path payload = storage.quarantinePayload(modpackId, entry.conflictId);
		String expectedPath = UpdatePlanner.normalize(storage.quarantinePackDirectory(modpackId).relativize(payload).toString());
		if (entry.quarantinePath == null || !expectedPath.equals(entry.quarantinePath)) throw new IOException("Client quarantine payload path is invalid");
		validateNoSymbolicLinkDescendants(storage.quarantinePackDirectory(modpackId), payload);
		if (!SmartFileUtils.isValidFile(payload, entry.sourceSize, entry.sourceHash)) throw new IOException("Client quarantine payload is missing or corrupt");
		if (action != ConflictAction.QUARANTINE) throw new IOException("Only quarantine actions may be stored in the quarantine archive");
	}

	private static void removeSourceIfPresent(ClientStorage storage, Conflict conflict, Path payload) throws IOException {
		Path source = storage.gamePath(conflict.sourcePath());
		if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
		if (!SmartFileUtils.isValidFile(source, conflict.sourceSize(), conflict.sourceHash())) throw new IOException("Quarantine source changed before removal: " + source);
		Files.delete(source);
		if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) || !SmartFileUtils.isValidFile(payload, conflict.sourceSize(), conflict.sourceHash()))
			throw new IOException("Quarantine source removal could not be verified: " + source);
	}

	private static boolean same(Jsons.ClientQuarantineFields.EntryFields entry, Conflict conflict) {
		return conflict.action() == ConflictAction.QUARANTINE && conflict.modIds().equals(new TreeSet<>(entry.modIds))
				&& conflict.sourcePath().equals(entry.sourcePath) && conflict.sourceHash().equalsIgnoreCase(entry.sourceHash) && conflict.sourceSize() == entry.sourceSize
				&& conflict.targetPath().equals(entry.targetPath) && conflict.targetHash().equalsIgnoreCase(entry.targetHash) && conflict.targetSize() == entry.targetSize;
	}

	private static void validateHash(String value, String description) throws IOException {
		if (value == null || !value.matches("[0-9a-fA-F]{40}")) throw new IOException("Invalid " + description);
	}

	private static void validateNoSymbolicLinkDescendants(Path root, Path target) throws IOException {
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path normalizedTarget = target.toAbsolutePath().normalize();
		if (!normalizedTarget.startsWith(normalizedRoot)) throw new IOException("Quarantine path escapes its pack root");
		Path current = normalizedRoot;
		if (Files.isSymbolicLink(current)) throw new IOException("Quarantine pack root is a symbolic link");
		for (Path component : normalizedRoot.relativize(normalizedTarget)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) throw new IOException("Quarantine path contains a symbolic link: " + current);
		}
	}
}
