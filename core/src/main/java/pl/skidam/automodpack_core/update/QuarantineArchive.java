package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.ConflictAction;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

/** Durable per-modpack storage for local files displaced by an ownership conflict. */
public final class QuarantineArchive {
	private static final Comparator<ClientStorageJsons.ClientQuarantineFields.EntryFields> ENTRY_ORDER = Comparator.comparing(entry -> entry.conflictId);
	private static final Object MUTATION_LOCK = new Object();

	private QuarantineArchive() {}

	public record ArchiveEntry(String conflictId, Set<String> modIds, String sourcePath, String sourceHash, long sourceSize, String targetPath,
			String targetHash, long targetSize, String sourceGenerationId, String quarantinedAt) {}

	public record Snapshot(String modpackId, List<ArchiveEntry> entries) {
		public Snapshot {
			modpackId = ModpackId.requireValid(modpackId);
			entries = List.copyOf(entries);
		}
	}

	public static void archive(ClientStorage storage, String generationId, Conflict conflict) throws IOException {
		synchronized (MUTATION_LOCK) {
			ClientStorageJsons.ClientQuarantineFields archive = read(storage, conflict.modpackId());
			ClientStorageJsons.ClientQuarantineFields.EntryFields existing = archive.entries.stream().filter(entry -> conflict.conflictId().equals(entry.conflictId)).findFirst().orElse(null);
			Path payload = storage.quarantinePayload(conflict.modpackId(), conflict.conflictId());
			if (existing != null) {
				validateEntryPayload(storage, conflict.modpackId(), existing);
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
			ClientStorageJsons.ClientQuarantineFields.EntryFields entry = toFields(storage, generationId, conflict);
			archive.entries = new ArrayList<>(archive.entries);
			archive.entries.add(entry);
			archive.entries.sort(ENTRY_ORDER);
			write(storage, conflict.modpackId(), archive);
			removeSourceIfPresent(storage, conflict, payload);
		}
	}

	public static ClientStorageJsons.ClientQuarantineFields read(ClientStorage storage, String modpackId) throws IOException {
		ClientStorageJsons.ClientQuarantineFields archive = readManifest(storage, modpackId);
		for (ClientStorageJsons.ClientQuarantineFields.EntryFields entry : archive.entries) validateEntryPayload(storage, archive.modpackId, entry);
		return archive;
	}

	/** Checks the validated manifest without hashing any archived payload. */
	public static boolean hasEntries(ClientStorage storage, String modpackId) throws IOException {
		return !readManifest(storage, modpackId).entries.isEmpty();
	}

	private static ClientStorageJsons.ClientQuarantineFields readManifest(ClientStorage storage, String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		Path root = storage.quarantinePackDirectory(normalizedModpackId);
		if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
			ClientStorageJsons.ClientQuarantineFields empty = new ClientStorageJsons.ClientQuarantineFields();
			empty.modpackId = normalizedModpackId;
			empty.entries = new ArrayList<>();
			return empty;
		}
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client quarantine root is invalid: " + root);
		Path manifest = storage.quarantineManifest(normalizedModpackId);
		if (Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client quarantine manifest is missing: " + manifest);
		if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client quarantine manifest is invalid: " + manifest);
		ClientStorageJsons.ClientQuarantineFields archive;
		try {
			archive = ConfigTools.read(manifest, ClientStorageJsons.ClientQuarantineFields.class).orElseThrow(() -> new IOException("Client quarantine manifest is empty"));
		} catch (RuntimeException e) {
			throw new IOException("Client quarantine manifest is invalid", e);
		}
		if (archive.schemaVersion != 1 || !normalizedModpackId.equals(archive.modpackId) || archive.entries == null)
			throw new IOException("Client quarantine manifest identity is invalid");
		Set<String> ids = new HashSet<>();
		for (ClientStorageJsons.ClientQuarantineFields.EntryFields entry : archive.entries) {
			if (entry == null || !ids.add(entry.conflictId)) throw new IOException("Client quarantine contains duplicate or incomplete entries");
			validateEntryMetadata(storage, normalizedModpackId, entry);
		}
		List<ClientStorageJsons.ClientQuarantineFields.EntryFields> sorted = new ArrayList<>(archive.entries);
		sorted.sort(ENTRY_ORDER);
		if (!sorted.equals(archive.entries)) throw new IOException("Client quarantine entries are not ordered");
		return archive;
	}

	public static Snapshot snapshot(ClientStorage storage, String modpackId) throws IOException {
		ClientStorageJsons.ClientQuarantineFields archive = read(storage, modpackId);
		return new Snapshot(archive.modpackId, archive.entries.stream().map(entry -> new ArchiveEntry(entry.conflictId, new TreeSet<>(entry.modIds), entry.sourcePath,
				entry.sourceHash.toLowerCase(Locale.ROOT), entry.sourceSize, entry.targetPath, entry.targetHash.toLowerCase(Locale.ROOT), entry.targetSize,
				entry.sourceGenerationId, entry.quarantinedAt)).toList());
	}

	/** Restores one local file only while its owning pack is active and no longer owns the source path. */
	public static void restore(ClientStorage storage, String modpackId, String conflictId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		if (conflictId == null || !conflictId.matches("[0-9a-f]{40}")) throw new IOException("Invalid quarantine conflict ID");
		synchronized (MUTATION_LOCK) {
			ClientStorageJsons.ClientQuarantineFields archive = read(storage, normalizedModpackId);
			ClientStorageJsons.ClientQuarantineFields.EntryFields entry = archive.entries.stream().filter(value -> conflictId.equals(value.conflictId)).findFirst()
					.orElseThrow(() -> new IOException("Quarantine entry is no longer available: " + conflictId));
			ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
			if (activeState == null || !normalizedModpackId.equals(activeState.modpackId))
				throw new IOException("The modpack must be active before a quarantined mod can be restored");
			SelectedModpackTarget activeTarget = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current())
					.orElseThrow(() -> new IOException("The active generation target is missing"));
			if (!normalizedModpackId.equals(activeTarget.manifest().modpackId())) throw new IOException("Active generation belongs to another modpack");
			if (activeOwnsPath(activeTarget, entry.sourcePath)) throw new IOException("The active modpack still owns " + entry.sourcePath);

			Path payload = storage.quarantinePayload(normalizedModpackId, entry.conflictId);
			Path destination = storage.gamePath(entry.sourcePath);
			validateNoSymbolicLinkDescendants(storage.quarantinePackDirectory(normalizedModpackId), payload);
			validateDestinationPath(storage.gameDirectory(), destination);
			if (!SmartFileUtils.isValidFile(payload, entry.sourceSize, entry.sourceHash)) throw new IOException("Quarantine payload is missing or corrupt: " + payload);

			boolean alreadyRestored = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
			if (alreadyRestored) {
				if (Files.isSymbolicLink(destination) || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Restore destination is not a regular file: " + destination);
				if (!SmartFileUtils.isValidFile(destination, entry.sourceSize, entry.sourceHash))
					throw new IOException("Restore destination contains different bytes: " + destination);
			} else {
				copyVerifiedCreateOnly(storage.gameDirectory(), payload, destination, entry.sourceSize, entry.sourceHash);
				validateDestinationPath(storage.gameDirectory(), destination);
				if (!SmartFileUtils.isValidFile(destination, entry.sourceSize, entry.sourceHash))
					throw new IOException("Restored destination failed verification: " + destination);
			}

			ClientStorageJsons.ClientQuarantineFields remaining = new ClientStorageJsons.ClientQuarantineFields();
			remaining.modpackId = normalizedModpackId;
			remaining.entries = archive.entries.stream().filter(value -> !conflictId.equals(value.conflictId)).toList();
			write(storage, normalizedModpackId, remaining);
			try {
				cleanupConsumedPayload(storage, normalizedModpackId, payload, entry.sourceSize, entry.sourceHash);
			} catch (IOException cleanupFailure) {
				try {
					write(storage, normalizedModpackId, archive);
				} catch (IOException rollbackFailure) {
					cleanupFailure.addSuppressed(rollbackFailure);
				}
				throw cleanupFailure;
			}
		}
	}

	private static boolean activeOwnsPath(SelectedModpackTarget activeTarget, String logicalPath) throws IOException {
		boolean projected = activeTarget.flatTarget().list != null && activeTarget.flatTarget().list.stream().anyMatch(item -> logicalPath.equals(UpdatePlanner.normalize(item.file)));
		if (!projected) return false;
		OwnershipLedger.Entry ledgerEntry = activeTarget.generationRecord().ownershipLedger().entries().get(logicalPath);
		if (ledgerEntry == null || ledgerEntry.currentStatus() != OwnershipLedger.Status.PRESENT)
			throw new IOException("Active target and ownership ledger disagree about " + logicalPath);
		return true;
	}

	private static void copyVerifiedCreateOnly(Path root, Path source, Path destination, long expectedSize, String expectedHash) throws IOException {
		Path parent = destination.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("Restore destination has no parent: " + destination);
		validateDestinationPath(root, destination);
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, "." + destination.getFileName() + ".", ".restore.tmp");
		try {
			validateDestinationPath(root, destination);
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			if (!SmartFileUtils.isValidFile(temporary, expectedSize, expectedHash)) throw new IOException("Restored temporary file failed verification: " + temporary);
			try {
				moveCreateOnly(temporary, destination);
			} catch (FileAlreadyExistsException raced) {
				validateDestinationPath(root, destination);
				if (!SmartFileUtils.isValidFile(destination, expectedSize, expectedHash)) throw new IOException("Restore destination contains different bytes: " + destination, raced);
			}
			validateDestinationPath(root, destination);
			if (!SmartFileUtils.isValidFile(destination, expectedSize, expectedHash)) throw new IOException("Restored destination failed verification: " + destination);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void moveCreateOnly(Path temporary, Path destination) throws IOException {
		try {
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(temporary, destination);
		}
	}

	private static void cleanupConsumedPayload(ClientStorage storage, String modpackId, Path payload, long expectedSize, String expectedHash) throws IOException {
		validateNoSymbolicLinkDescendants(storage.quarantinePackDirectory(modpackId), payload);
		if (Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Consumed quarantine payload is not a regular file: " + payload);
			if (!SmartFileUtils.isValidFile(payload, expectedSize, expectedHash)) throw new IOException("Consumed quarantine payload changed: " + payload);
			Files.delete(payload);
		}
		Path conflictDirectory = payload.getParent();
		if (conflictDirectory != null && Files.isDirectory(conflictDirectory, LinkOption.NOFOLLOW_LINKS)) {
			try {
				Files.deleteIfExists(conflictDirectory);
			} catch (IOException ignored) {
				return;
			}
			Path conflictsDirectory = conflictDirectory.getParent();
			if (conflictsDirectory != null && Files.isDirectory(conflictsDirectory, LinkOption.NOFOLLOW_LINKS)) {
				try {
					Files.deleteIfExists(conflictsDirectory);
				} catch (IOException ignored) {
					// Empty-directory cleanup is not part of the archive transaction.
				}
			}
		}
	}

	private static void validateDestinationPath(Path root, Path destination) throws IOException {
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path normalizedDestination = destination.toAbsolutePath().normalize();
		if (!normalizedDestination.startsWith(normalizedRoot)) throw new IOException("Restore destination escaped the game directory");
		Path current = normalizedRoot;
		if (Files.isSymbolicLink(current)) throw new IOException("Restore root is a symbolic link: " + current);
		for (Path component : normalizedRoot.relativize(normalizedDestination)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) throw new IOException("Restore path contains a symbolic link: " + current);
		}
	}

	private static void write(ClientStorage storage, String modpackId, ClientStorageJsons.ClientQuarantineFields archive) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		archive.schemaVersion = 1;
		archive.modpackId = normalizedModpackId;
		Files.createDirectories(storage.quarantinePackDirectory(normalizedModpackId));
		ConfigTools.writeAtomic(storage.quarantineManifest(normalizedModpackId), archive);
		read(storage, normalizedModpackId);
	}

	private static ClientStorageJsons.ClientQuarantineFields.EntryFields toFields(ClientStorage storage, String generationId, Conflict conflict) {
		ClientStorageJsons.ClientQuarantineFields.EntryFields entry = new ClientStorageJsons.ClientQuarantineFields.EntryFields();
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

	private static void validateEntryMetadata(ClientStorage storage, String modpackId, ClientStorageJsons.ClientQuarantineFields.EntryFields entry) throws IOException {
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
		if (action != ConflictAction.QUARANTINE) throw new IOException("Only quarantine actions may be stored in the quarantine archive");
	}

	private static void validateEntryPayload(ClientStorage storage, String modpackId, ClientStorageJsons.ClientQuarantineFields.EntryFields entry) throws IOException {
		validateEntryMetadata(storage, modpackId, entry);
		Path payload = storage.quarantinePayload(modpackId, entry.conflictId);
		validateNoSymbolicLinkDescendants(storage.quarantinePackDirectory(modpackId), payload);
		if (!SmartFileUtils.isValidFile(payload, entry.sourceSize, entry.sourceHash)) throw new IOException("Client quarantine payload is missing or corrupt");
	}

	private static void removeSourceIfPresent(ClientStorage storage, Conflict conflict, Path payload) throws IOException {
		Path source = storage.gamePath(conflict.sourcePath());
		if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
		if (!SmartFileUtils.isValidFile(source, conflict.sourceSize(), conflict.sourceHash())) throw new IOException("Quarantine source changed before removal: " + source);
		Files.delete(source);
		if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) || !SmartFileUtils.isValidFile(payload, conflict.sourceSize(), conflict.sourceHash()))
			throw new IOException("Quarantine source removal could not be verified: " + source);
	}

	private static boolean same(ClientStorageJsons.ClientQuarantineFields.EntryFields entry, Conflict conflict) {
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
