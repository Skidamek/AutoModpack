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
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/**
 * Owns durable user-recoverable claims and the CAS bytes that satisfy them.
 *
 * <p>
 * Every mutation runs under the game-directory mutation lock, like every other durable client
 * mutation. The nested preserve calls reuse the same held lock instead of a separate lock regime.
 * The vault keeps one claim per source path and content: re-preserving bytes it already holds
 * supersedes the older claim's provenance, while different bytes for the same path stay
 * separately recoverable.
 * </p>
 */
public final class PreservationVault {
	private static final Comparator<ClientStorageJsons.ClientPreservationVaultFields.ClaimFields> CLAIM_ORDER = Comparator.comparing(claim -> claim.claimId);

	private PreservationVault() {}

	public enum Reason {
		SERVER_REMOVAL,
		MODPACK_REMOVAL,
		MODPACK_DEACTIVATION,
		LOCAL_CONFLICT,
		PLAYER_CONSENT,
		STRICT_REPAIR,
		EDITABLE_RESET,
		LOCAL_DRIFT
	}

	public record Claim(String claimId, String originalPath, Root sourceRoot, String objectHash, long size, String modpackId, String contentToken, Reason reason,
			Instant preservedAt) {
		public Claim {
			Objects.requireNonNull(sourceRoot, "source root");
			Objects.requireNonNull(reason, "preservation reason");
			Objects.requireNonNull(preservedAt, "preservation time");
		}
	}

	public record Snapshot(String modpackId, List<Claim> claims) {
		public Snapshot {
			modpackId = ModpackId.requireValid(modpackId);
			claims = List.copyOf(claims);
		}
	}

	public static Claim preserve(ClientStorage storage, String modpackId, String contentToken, Reason reason, Root sourceRoot, String originalPath, String objectHash, long size)
			throws IOException {
		return preserve(storage, modpackId, contentToken, reason, sourceRoot, originalPath, objectHash, size, Instant.now());
	}

	static Claim preserve(ClientStorage storage, String modpackId, String contentToken, Reason reason, Root sourceRoot, String originalPath, String objectHash, long size,
			Instant preservedAt) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return ClientStorageMutation.run(storage, () -> preserveLocked(storage, modpackId, contentToken, reason, sourceRoot, originalPath, objectHash, size, preservedAt));
	}

	private static Claim preserveLocked(ClientStorage storage, String modpackId, String contentToken, Reason reason, Root sourceRoot, String originalPath, String objectHash, long size,
			Instant preservedAt) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		String generation = requireOptionalHash(contentToken, "preservation generation ID");
		Reason normalizedReason = Objects.requireNonNull(reason, "preservation reason");
		Root normalizedRoot = requireRestorableRoot(sourceRoot);
		String path = requirePath(originalPath);
		String hash = requireHash(objectHash, "preservation object hash");
		if (size < 0) throw new IOException("Preservation object size is invalid");
		Instant time = requireInstant(preservedAt);
		String claimId = claimId(pack, generation, normalizedReason, normalizedRoot, path, hash, size);

		{
			try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
				ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
				ClientStorageJsons.ClientPreservationVaultFields.ClaimFields existing = fields.claims.stream().filter(claim -> claimId.equals(claim.claimId)).findFirst().orElse(null);
				Path source = source(storage, pack, normalizedRoot, path);
				Path object = object(storage, hash);
				if (existing != null) {
					if (!FileIntegrity.matchesNamed(object, size, hash, cache)) repairObjectFromSource(storage, source, object, hash, size, cache);
					if (!FileIntegrity.matchesNamed(object, size, hash, cache)) throw new IOException("Preserved object is missing or corrupt: " + hash);
					return toClaim(existing);
				}

				validateSource(storage, pack, normalizedRoot, source);
				if (!FileIntegrity.matches(source, size, hash, cache)) throw new IOException("Preservation source changed after planning: " + source);
				if (!FileIntegrity.matchesNamed(object, size, hash, cache)) VerifiedFileTransfer.copyAtomicImmutable(source, object, size, hash, cache);
				if (!FileIntegrity.matchesNamed(object, size, hash, cache)) throw new IOException("Preserved object verification failed: " + object);

				// The vault keeps one recoverable claim per path and content: a new receipt for bytes it
				// already holds supersedes the older provenance instead of duplicating the row.
				fields.claims = new ArrayList<>(fields.claims.stream().filter(held -> claimId.equals(held.claimId) || !sameContent(held, normalizedRoot, path, hash, size)).toList());
				ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim = new ClientStorageJsons.ClientPreservationVaultFields.ClaimFields();
				claim.claimId = claimId;
				claim.originalPath = path;
				claim.sourceRoot = normalizedRoot.name();
				claim.objectHash = hash;
				claim.size = size;
				claim.modpackId = pack;
				claim.contentToken = generation;
				claim.reason = normalizedReason.name();
				claim.preservedAt = time.toString();
				fields.claims = new ArrayList<>(fields.claims);
				fields.claims.add(claim);
				fields.claims.sort(CLAIM_ORDER);
				write(storage, pack, fields);
				return toClaim(claim);
			}
		}
	}

	/** Preserves and then removes a conflicting local file. Retrying the same conflict is idempotent. */
	public static Claim preserveConflict(ClientStorage storage, String contentToken, Conflict conflict) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
				Claim claim = preserve(storage, conflict.modpackId(), contentToken, Reason.LOCAL_CONFLICT, Root.GAME_DIR, conflict.sourcePath(), conflict.sourceHash(), conflict.sourceSize());
				Path source = storage.gamePath(conflict.sourcePath());
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
					validateSource(storage, conflict.modpackId(), Root.GAME_DIR, source);
					if (!FileIntegrity.matches(source, conflict.sourceSize(), conflict.sourceHash(), cache)) throw new IOException("Conflict source changed before removal: " + source);
					Files.delete(source);
				}
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) || !FileIntegrity.matchesNamed(object(storage, claim.objectHash()), claim.size(), claim.objectHash(), cache))
					throw new IOException("Conflict source removal could not be verified: " + source);
				return claim;
			}
		});
	}

	/** Replaces the claim for a path with one for new bytes as one vault operation: a claim already matching the new bytes is returned unchanged, superseded claims are released. */
	public static Claim replaceClaim(ClientStorage storage, String modpackId, String contentToken, Reason reason, Root sourceRoot, String originalPath, String objectHash, long size)
			throws IOException {
		Objects.requireNonNull(storage, "storage");
		String pack = ModpackId.requireValid(modpackId);
		Reason normalizedReason = Objects.requireNonNull(reason, "preservation reason");
		Root normalizedRoot = requireRestorableRoot(sourceRoot);
		if (size < 0) throw new IOException("Preservation object size is invalid");
		return ClientStorageMutation.run(storage, () -> {
			for (Claim claim : read(storage, pack).claims()) {
				if (claim.reason() != normalizedReason || claim.sourceRoot() != normalizedRoot) continue;
				if (!UpdatePlanner.normalize(claim.originalPath()).equals(UpdatePlanner.normalize(originalPath))) continue;
				if (claim.objectHash().equalsIgnoreCase(objectHash) && claim.size() == size) return claim;
				delete(storage, pack, claim.claimId());
			}
			return preserve(storage, pack, contentToken, normalizedReason, normalizedRoot, originalPath, objectHash, size);
		});
	}

	/** Preserves and removes a regular file as one idempotent vault operation. */
	public static Claim preserveAndRemove(ClientStorage storage, String modpackId, String contentToken, Reason reason, Root sourceRoot, String originalPath, String objectHash,
			long size) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
				Claim claim = preserve(storage, modpackId, contentToken, reason, sourceRoot, originalPath, objectHash, size);
				Path source = source(storage, modpackId, sourceRoot, originalPath);
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
					validateSource(storage, modpackId, sourceRoot, source);
					if (!FileIntegrity.matches(source, size, objectHash, cache)) throw new IOException("Preservation source changed before removal: " + source);
					Files.delete(source);
				}
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) || !FileIntegrity.matchesNamed(object(storage, claim.objectHash()), claim.size(), claim.objectHash(), cache))
					throw new IOException("Preserved source removal could not be verified: " + source);
				return claim;
			}
		});
	}

	public static Snapshot read(ClientStorage storage, String modpackId) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
		List<Claim> claims = new ArrayList<>();
		for (ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim : fields.claims) claims.add(toClaim(claim));
		return new Snapshot(pack, claims);
	}

	/** Returns the current-format modpack IDs that have at least one preserved claim. */
	public static List<String> modpackIds(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		Path root = storage.preservationDirectory().toAbsolutePath().normalize();
		FileTrees.requireNoSymbolicLinkDescendants(root, root, "preservation vault");
		if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Preservation vault is not a directory: " + root);

		List<String> packs = new ArrayList<>();
		try (var paths = Files.list(root)) {
			for (Path packRoot : paths.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
				FileTrees.requireNoSymbolicLinkDescendants(root, packRoot, "preservation vault pack");
				if (!Files.isDirectory(packRoot, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Preservation vault pack is not a directory: " + packRoot);
				String pack;
				try {
					pack = ModpackId.requireValid(packRoot.getFileName().toString());
				} catch (RuntimeException e) {
					throw new IOException("Preservation vault pack identity is invalid: " + packRoot, e);
				}
				if (!read(storage, pack).claims().isEmpty()) packs.add(pack);
			}
		}
		return List.copyOf(packs);
	}

	/** Returns every claim-bearing vault snapshot without manufacturing an installed generation. */
	public static List<Snapshot> snapshots(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		List<Snapshot> snapshots = new ArrayList<>();
		for (String modpackId : modpackIds(storage)) snapshots.add(read(storage, modpackId));
		return List.copyOf(snapshots);
	}

	/** Restores to the original game path only when the same pack is active, the path is unowned, and no different file would be overwritten. Success releases the claim. */
	public static Path restoreOriginal(ClientStorage storage, String modpackId, String claimId) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		String id = requireHash(claimId, "preservation claim ID");
		return ClientStorageMutation.run(storage, () -> {
			try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
				ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
				ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim = requireClaim(fields, id);
				if (Root.valueOf(claim.sourceRoot) != Root.GAME_DIR) throw new IOException("Only game-directory claims can be restored to their original path");
				requireActiveUnownedPath(storage, pack, claim.originalPath);
				Path destination = storage.gamePath(claim.originalPath);
				copyWithoutOverwrite(storage.gameDirectory(), object(storage, claim.objectHash), destination, claim.size, claim.objectHash, cache);
				releaseClaim(storage, pack, fields, id);
				return destination;
			}
		});
	}

	/** Saves a deterministic copy without changing the active modpack. Success releases the claim. */
	public static Path saveCopy(ClientStorage storage, String modpackId, String claimId) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		String id = requireHash(claimId, "preservation claim ID");
		return ClientStorageMutation.run(storage, () -> {
			try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
				ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
				ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim = requireClaim(fields, id);
				Path root = storage.restoredClaimDirectory(pack, claim.contentToken, id);
				Path destination = LogicalPath.resolve(root, claim.originalPath);
				copyWithoutOverwrite(storage.gameDirectory(), object(storage, claim.objectHash), destination, claim.size, claim.objectHash, cache);
				releaseClaim(storage, pack, fields, id);
				return destination;
			}
		});
	}

	/** Explicitly releases one durable claim. Its bytes remain until the next explicit CAS collection. */
	public static void delete(ClientStorage storage, String modpackId, String claimId) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		String id = requireHash(claimId, "preservation claim ID");
		ClientStorageMutation.run(storage, () -> {
			ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
			requireClaim(fields, id);
			releaseClaim(storage, pack, fields, id);
			return null;
		});
	}

	private static void requireActiveUnownedPath(ClientStorage storage, String modpackId, String logicalPath) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState == null || !modpackId.equals(activeState.modpackId)) throw new IOException("The modpack must be active before a file can be restored to its original path");
		SelectedModpackTarget activeTarget = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current())
				.orElseThrow(() -> new IOException("The active generation target is missing"));
		if (!modpackId.equals(activeTarget.manifest().modpackId())) throw new IOException("Active generation belongs to another modpack");
		String contentToken = activeTarget.packTarget().contentToken();
		String selectionDigest = UpdateTransaction.digest(activeTarget.selection().intent());
		boolean generated = GeneratedCopyState.read(storage, modpackId, contentToken, selectionDigest).entries().stream()
				.anyMatch(entry -> logicalPath.equals(entry.logicalPath()));
		if (generated) throw new IOException("The active modpack still owns generated file " + logicalPath);
		boolean projected = activeTarget.flatTarget().list != null && activeTarget.flatTarget().list.stream().anyMatch(item -> logicalPath.equals(UpdatePlanner.normalize(item.file)));
		if (!projected) return;
		OwnershipLedger.Entry ledgerEntry = activeTarget.document().ownershipLedger().entries().get(logicalPath);
		if (ledgerEntry == null || ledgerEntry.currentStatus() != OwnershipLedger.Status.PRESENT) throw new IOException("Active target and ownership ledger disagree about " + logicalPath);
		throw new IOException("The active modpack still owns " + logicalPath);
	}

	private static void copyWithoutOverwrite(Path constrainedRoot, Path source, Path destination, long size, String hash, FileMetadataCache cache) throws IOException {
		FileTrees.requireNoSymbolicLinkDescendants(constrainedRoot, destination, "restore destination");
		if (!FileIntegrity.matchesNamed(source, size, hash, cache)) throw new IOException("Preserved object is missing or corrupt: " + hash);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !FileIntegrity.matches(destination, size, hash, cache))
				throw new IOException("Restore destination already exists: " + destination);
			return;
		}
		VerifiedFileTransfer.copyCreateOnly(source, destination, size, hash, cache);
		FileTrees.requireNoSymbolicLinkDescendants(constrainedRoot, destination, "restore destination");
		if (!FileIntegrity.matches(destination, size, hash, cache)) throw new IOException("Restored file failed verification: " + destination);
	}

	private static boolean sameContent(ClientStorageJsons.ClientPreservationVaultFields.ClaimFields held, Root root, String path, String hash, long size) {
		return root.name().equals(held.sourceRoot) && path.equals(held.originalPath) && held.objectHash != null && hash.equalsIgnoreCase(held.objectHash) && held.size == size;
	}

	private static void releaseClaim(ClientStorage storage, String modpackId, ClientStorageJsons.ClientPreservationVaultFields fields, String claimId) throws IOException {
		fields.claims = fields.claims.stream().filter(claim -> !claimId.equals(claim.claimId)).toList();
		write(storage, modpackId, fields);
	}

	private static ClientStorageJsons.ClientPreservationVaultFields.ClaimFields requireClaim(ClientStorageJsons.ClientPreservationVaultFields fields, String claimId) throws IOException {
		return fields.claims.stream().filter(claim -> claimId.equals(claim.claimId)).findFirst().orElseThrow(() -> new IOException("Preservation claim is no longer available: " + claimId));
	}

	private static ClientStorageJsons.ClientPreservationVaultFields readFields(ClientStorage storage, String modpackId) throws IOException {
		Path root = storage.preservationPackDirectory(modpackId);
		FileTrees.requireNoSymbolicLinkDescendants(storage.preservationDirectory(), root, "preservation vault");
		Path manifest = storage.preservationManifest(modpackId);
		if (Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS)) {
			ClientStorageJsons.ClientPreservationVaultFields empty = new ClientStorageJsons.ClientPreservationVaultFields();
			empty.modpackId = modpackId;
			empty.claims = new ArrayList<>();
			return empty;
		}
		FileTrees.requireNoSymbolicLinkDescendants(root, manifest, "preservation manifest");
		if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Preservation manifest is not a regular file: " + manifest);
		ClientStorageJsons.ClientPreservationVaultFields fields;
		try {
			fields = ConfigTools.read(manifest, ClientStorageJsons.ClientPreservationVaultFields.class).orElseThrow(() -> new IOException("Preservation manifest is empty"));
		} catch (RuntimeException e) {
			throw new IOException("Preservation manifest is invalid", e);
		}
		if (fields.schemaVersion != 1 || !modpackId.equals(fields.modpackId) || fields.claims == null) throw new IOException("Preservation manifest identity is invalid");
		Set<String> ids = new HashSet<>();
		for (ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim : fields.claims) {
			Claim parsed = toClaim(claim);
			if (!modpackId.equals(parsed.modpackId()) || !ids.add(parsed.claimId())) throw new IOException("Preservation manifest contains duplicate or foreign claims");
			String expectedId = claimId(parsed.modpackId(), parsed.contentToken(), parsed.reason(), parsed.sourceRoot(), parsed.originalPath(), parsed.objectHash(), parsed.size());
			if (!expectedId.equals(parsed.claimId())) throw new IOException("Preservation claim identity is invalid");
		}
		List<ClientStorageJsons.ClientPreservationVaultFields.ClaimFields> sorted = new ArrayList<>(fields.claims);
		sorted.sort(CLAIM_ORDER);
		if (!sorted.equals(fields.claims)) throw new IOException("Preservation claims are not deterministically ordered");
		return fields;
	}

	private static void write(ClientStorage storage, String modpackId, ClientStorageJsons.ClientPreservationVaultFields fields) throws IOException {
		Path root = storage.preservationPackDirectory(modpackId);
		FileTrees.requireNoSymbolicLinkDescendants(storage.preservationDirectory(), root, "preservation vault");
		Files.createDirectories(root);
		FileTrees.requireNoSymbolicLinkDescendants(storage.preservationDirectory(), root, "preservation vault");
		fields.schemaVersion = 1;
		fields.modpackId = modpackId;
		ConfigTools.writeAtomic(storage.preservationManifest(modpackId), fields);
		readFields(storage, modpackId);
	}

	private static Claim toClaim(ClientStorageJsons.ClientPreservationVaultFields.ClaimFields fields) throws IOException {
		if (fields == null) throw new IOException("Preservation claim is missing");
		String id = requireHash(fields.claimId, "preservation claim ID");
		String path = requirePath(fields.originalPath);
		Root root;
		Reason reason;
		try {
			root = requireRestorableRoot(Root.valueOf(fields.sourceRoot));
			reason = Reason.valueOf(fields.reason);
		} catch (RuntimeException e) {
			throw new IOException("Preservation claim classification is invalid", e);
		}
		String hash = requireHash(fields.objectHash, "preservation object hash");
		if (fields.size < 0) throw new IOException("Preservation claim size is invalid");
		String pack = ModpackId.requireValid(fields.modpackId);
		String generation = requireOptionalHash(fields.contentToken, "preservation generation ID");
		Instant time;
		try {
			time = requireInstant(Instant.parse(fields.preservedAt));
		} catch (RuntimeException e) {
			throw new IOException("Preservation timestamp is invalid", e);
		}
		return new Claim(id, path, root, hash, fields.size, pack, generation, reason, time);
	}

	private static String claimId(String modpackId, String contentToken, Reason reason, Root sourceRoot, String path, String hash, long size) {
		return HashUtils.sha1("automodpack-preservation-v1\nmodpack=" + modpackId + "\ngeneration=" + contentToken + "\nreason=" + reason.name() + "\nroot=" + sourceRoot.name()
				+ "\npath=" + path + "\nhash=" + hash + "\nsize=" + size + "\n");
	}

	private static Path source(ClientStorage storage, String modpackId, Root root, String path) {
		return switch (root) {
			case GAME_DIR -> storage.gamePath(path);
			case OVERLAY -> storage.overlayFile(modpackId, path);
			case PROJECTION -> storage.activePath(path);
		};
	}

	private static void validateSource(ClientStorage storage, String modpackId, Root root, Path source) throws IOException {
		Path constrainedRoot = switch (root) {
			case GAME_DIR -> storage.gameDirectory();
			case OVERLAY -> storage.overlayDirectory(modpackId);
			case PROJECTION -> storage.activeDirectory();
		};
		FileTrees.requireNoSymbolicLinkDescendants(constrainedRoot, source, "preservation source");
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Preservation source is not a regular file: " + source);
	}

	private static void repairObjectFromSource(ClientStorage storage, Path source, Path object, String hash, long size, FileMetadataCache cache) throws IOException {
		Path sourceRoot = source.startsWith(storage.gameDirectory()) ? storage.gameDirectory() : storage.clientDirectory();
		FileTrees.requireNoSymbolicLinkDescendants(sourceRoot, source, "preservation source");
		if (!FileIntegrity.matches(source, size, hash, cache)) throw new IOException("Preserved object is corrupt and its source is unavailable: " + hash);
		VerifiedFileTransfer.copyAtomicImmutable(source, object, size, hash, cache);
	}

	private static Path object(ClientStorage storage, String hash) throws IOException {
		Path root = storage.objectsDirectory().toAbsolutePath().normalize();
		Path object = storage.objectFile(requireHash(hash, "preservation object hash"));
		FileTrees.requireNoSymbolicLinkDescendants(root, object, "preservation object");
		return object;
	}

	private static Root requireRestorableRoot(Root root) throws IOException {
		if (root == null) throw new IOException("Preservation source root is missing");
		return root;
	}

	private static String requirePath(String path) throws IOException {
		try {
			return LogicalPath.requireCanonical(path);
		} catch (RuntimeException e) {
			throw new IOException("Preservation path is invalid", e);
		}
	}

	private static String requireHash(String hash, String description) throws IOException {
		if (!HashUtils.isCanonicalSha1(hash)) throw new IOException("Invalid " + description);
		return hash.toLowerCase(Locale.ROOT);
	}

	private static String requireOptionalHash(String hash, String description) throws IOException {
		if (hash == null || hash.isEmpty()) return "";
		return requireHash(hash, description);
	}

	private static Instant requireInstant(Instant instant) throws IOException {
		if (instant == null || !Instant.parse(instant.toString()).equals(instant)) throw new IOException("Preservation timestamp is invalid");
		return instant;
	}
}
