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
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

/** Owns durable user-recoverable claims and the CAS bytes that satisfy them. */
public final class PreservationVault {
	private static final Comparator<ClientStorageJsons.ClientPreservationVaultFields.ClaimFields> CLAIM_ORDER = Comparator.comparing(claim -> claim.claimId);
	private static final Object MUTATION_LOCK = new Object();

	private PreservationVault() {}

	public enum Reason {
		SERVER_REMOVAL,
		MODPACK_REMOVAL,
		MODPACK_DEACTIVATION,
		LOCAL_CONFLICT,
		STRICT_INSTALL,
		STRICT_REPAIR,
		EDITABLE_RESET
	}

	public enum Status {
		AVAILABLE,
		RESTORED,
		SAVED_COPY
	}

	public record Claim(String claimId, String originalPath, Root sourceRoot, String objectHash, long size, String modpackId, String generationId, Reason reason,
			Instant preservedAt, Status status) {
		public Claim {
			Objects.requireNonNull(sourceRoot, "source root");
			Objects.requireNonNull(reason, "preservation reason");
			Objects.requireNonNull(preservedAt, "preservation time");
			Objects.requireNonNull(status, "preservation status");
		}
	}

	public record Snapshot(String modpackId, List<Claim> claims) {
		public Snapshot {
			modpackId = ModpackId.requireValid(modpackId);
			claims = List.copyOf(claims);
		}
	}

	public static Claim preserve(ClientStorage storage, String modpackId, String generationId, Reason reason, Root sourceRoot, String originalPath, String objectHash, long size)
			throws IOException {
		return preserve(storage, modpackId, generationId, reason, sourceRoot, originalPath, objectHash, size, Instant.now());
	}

	static Claim preserve(ClientStorage storage, String modpackId, String generationId, Reason reason, Root sourceRoot, String originalPath, String objectHash, long size,
			Instant preservedAt) throws IOException {
		Objects.requireNonNull(storage, "storage");
		String pack = ModpackId.requireValid(modpackId);
		String generation = requireOptionalHash(generationId, "preservation generation ID");
		Reason normalizedReason = Objects.requireNonNull(reason, "preservation reason");
		Root normalizedRoot = requireRestorableRoot(sourceRoot);
		String path = requirePath(originalPath);
		String hash = requireHash(objectHash, "preservation object hash");
		if (size < 0) throw new IOException("Preservation object size is invalid");
		Instant time = requireInstant(preservedAt);
		String claimId = claimId(pack, generation, normalizedReason, normalizedRoot, path, hash, size);

		synchronized (MUTATION_LOCK) {
			ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
			ClientStorageJsons.ClientPreservationVaultFields.ClaimFields existing = fields.claims.stream().filter(claim -> claimId.equals(claim.claimId)).findFirst().orElse(null);
			Path source = source(storage, pack, normalizedRoot, path);
			Path object = object(storage, hash);
			if (existing != null) {
				if (!FileIntegrity.matches(object, size, hash)) repairObjectFromSource(storage, source, object, hash, size);
				if (!FileIntegrity.matches(object, size, hash)) throw new IOException("Preserved object is missing or corrupt: " + hash);
				return toClaim(existing);
			}

			validateSource(storage, pack, normalizedRoot, source);
			if (!FileIntegrity.matches(source, size, hash)) throw new IOException("Preservation source changed after planning: " + source);
			if (!FileIntegrity.matches(object, size, hash)) VerifiedFileTransfer.copyAtomicImmutable(source, object, size, hash);
			if (!FileIntegrity.matches(object, size, hash)) throw new IOException("Preserved object verification failed: " + object);

			ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim = new ClientStorageJsons.ClientPreservationVaultFields.ClaimFields();
			claim.claimId = claimId;
			claim.originalPath = path;
			claim.sourceRoot = normalizedRoot.name();
			claim.objectHash = hash;
			claim.size = size;
			claim.modpackId = pack;
			claim.generationId = generation;
			claim.reason = normalizedReason.name();
			claim.preservedAt = time.toString();
			claim.status = Status.AVAILABLE.name();
			fields.claims = new ArrayList<>(fields.claims);
			fields.claims.add(claim);
			fields.claims.sort(CLAIM_ORDER);
			write(storage, pack, fields);
			return toClaim(claim);
		}
	}

	/** Preserves and then removes a conflicting local file. Retrying the same conflict is idempotent. */
	public static Claim preserveConflict(ClientStorage storage, String generationId, Conflict conflict) throws IOException {
		synchronized (MUTATION_LOCK) {
			Claim claim = preserve(storage, conflict.modpackId(), generationId, Reason.LOCAL_CONFLICT, Root.GAME_DIR, conflict.sourcePath(), conflict.sourceHash(), conflict.sourceSize());
			Path source = storage.gamePath(conflict.sourcePath());
			if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
				validateSource(storage, conflict.modpackId(), Root.GAME_DIR, source);
				if (!FileIntegrity.matches(source, conflict.sourceSize(), conflict.sourceHash())) throw new IOException("Conflict source changed before removal: " + source);
				Files.delete(source);
			}
			if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) || !FileIntegrity.matches(object(storage, claim.objectHash()), claim.size(), claim.objectHash()))
				throw new IOException("Conflict source removal could not be verified: " + source);
			return claim;
		}
	}

	/** Preserves and removes a regular file as one idempotent vault operation. */
	public static Claim preserveAndRemove(ClientStorage storage, String modpackId, String generationId, Reason reason, Root sourceRoot, String originalPath, String objectHash,
			long size) throws IOException {
		synchronized (MUTATION_LOCK) {
			Claim claim = preserve(storage, modpackId, generationId, reason, sourceRoot, originalPath, objectHash, size);
			Path source = source(storage, modpackId, sourceRoot, originalPath);
			if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
				validateSource(storage, modpackId, sourceRoot, source);
				if (!FileIntegrity.matches(source, size, objectHash)) throw new IOException("Preservation source changed before removal: " + source);
				Files.delete(source);
			}
			if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) || !FileIntegrity.matches(object(storage, claim.objectHash()), claim.size(), claim.objectHash()))
				throw new IOException("Preserved source removal could not be verified: " + source);
			return claim;
		}
	}

	public static Snapshot read(ClientStorage storage, String modpackId) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
		List<Claim> claims = new ArrayList<>();
		for (ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim : fields.claims) claims.add(toClaim(claim));
		return new Snapshot(pack, claims);
	}

	/** Restores to the original game path only when the same pack is active, the path is unowned, and no different file would be overwritten. */
	public static Path restoreOriginal(ClientStorage storage, String modpackId, String claimId) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		String id = requireHash(claimId, "preservation claim ID");
		synchronized (MUTATION_LOCK) {
			ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
			ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim = requireClaim(fields, id);
			if (Root.valueOf(claim.sourceRoot) != Root.GAME_DIR) throw new IOException("Only game-directory claims can be restored to their original path");
			requireActiveUnownedPath(storage, pack, claim.originalPath);
			Path destination = storage.gamePath(claim.originalPath);
			copyWithoutOverwrite(storage.gameDirectory(), object(storage, claim.objectHash), destination, claim.size, claim.objectHash);
			setStatus(storage, pack, fields, claim, Status.RESTORED);
			return destination;
		}
	}

	/** Saves a deterministic copy without changing the active modpack or consuming the claim. */
	public static Path saveCopy(ClientStorage storage, String modpackId, String claimId) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		String id = requireHash(claimId, "preservation claim ID");
		synchronized (MUTATION_LOCK) {
			ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
			ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim = requireClaim(fields, id);
			Path root = storage.restoredClaimDirectory(pack, claim.generationId, id);
			Path destination = LogicalPath.resolve(root, claim.originalPath);
			copyWithoutOverwrite(storage.gameDirectory(), object(storage, claim.objectHash), destination, claim.size, claim.objectHash);
			setStatus(storage, pack, fields, claim, Status.SAVED_COPY);
			return destination;
		}
	}

	/** Explicitly releases one durable claim. Its bytes remain until the next explicit CAS collection. */
	public static void delete(ClientStorage storage, String modpackId, String claimId) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		String id = requireHash(claimId, "preservation claim ID");
		synchronized (MUTATION_LOCK) {
			ClientStorageJsons.ClientPreservationVaultFields fields = readFields(storage, pack);
			requireClaim(fields, id);
			fields.claims = fields.claims.stream().filter(claim -> !id.equals(claim.claimId)).toList();
			write(storage, pack, fields);
		}
	}

	private static void requireActiveUnownedPath(ClientStorage storage, String modpackId, String logicalPath) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState == null || !modpackId.equals(activeState.modpackId)) throw new IOException("The modpack must be active before a file can be restored to its original path");
		SelectedModpackTarget activeTarget = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current())
				.orElseThrow(() -> new IOException("The active generation target is missing"));
		if (!modpackId.equals(activeTarget.manifest().modpackId())) throw new IOException("Active generation belongs to another modpack");
		String generationId = activeTarget.generationTarget().targetGenerationId();
		String selectionDigest = UpdateTransaction.digest(activeTarget.selection().intent());
		boolean generated = GeneratedCopyState.read(storage, modpackId, generationId, selectionDigest).entries().stream()
				.anyMatch(entry -> logicalPath.equals(entry.logicalPath()));
		if (generated) throw new IOException("The active modpack still owns generated file " + logicalPath);
		boolean projected = activeTarget.flatTarget().list != null && activeTarget.flatTarget().list.stream().anyMatch(item -> logicalPath.equals(UpdatePlanner.normalize(item.file)));
		if (!projected) return;
		OwnershipLedger.Entry ledgerEntry = activeTarget.generationRecord().ownershipLedger().entries().get(logicalPath);
		if (ledgerEntry == null || ledgerEntry.currentStatus() != OwnershipLedger.Status.PRESENT) throw new IOException("Active target and ownership ledger disagree about " + logicalPath);
		throw new IOException("The active modpack still owns " + logicalPath);
	}

	private static void copyWithoutOverwrite(Path constrainedRoot, Path source, Path destination, long size, String hash) throws IOException {
		validateNoSymbolicLinkDescendants(constrainedRoot, destination, "restore destination");
		if (!FileIntegrity.matches(source, size, hash)) throw new IOException("Preserved object is missing or corrupt: " + hash);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !FileIntegrity.matches(destination, size, hash))
				throw new IOException("Restore destination already exists: " + destination);
			return;
		}
		VerifiedFileTransfer.copyCreateOnly(source, destination, size, hash);
		validateNoSymbolicLinkDescendants(constrainedRoot, destination, "restore destination");
		if (!FileIntegrity.matches(destination, size, hash)) throw new IOException("Restored file failed verification: " + destination);
	}

	private static void setStatus(ClientStorage storage, String modpackId, ClientStorageJsons.ClientPreservationVaultFields fields,
			ClientStorageJsons.ClientPreservationVaultFields.ClaimFields claim, Status status) throws IOException {
		if (status.name().equals(claim.status)) return;
		claim.status = status.name();
		write(storage, modpackId, fields);
	}

	private static ClientStorageJsons.ClientPreservationVaultFields.ClaimFields requireClaim(ClientStorageJsons.ClientPreservationVaultFields fields, String claimId) throws IOException {
		return fields.claims.stream().filter(claim -> claimId.equals(claim.claimId)).findFirst().orElseThrow(() -> new IOException("Preservation claim is no longer available: " + claimId));
	}

	private static ClientStorageJsons.ClientPreservationVaultFields readFields(ClientStorage storage, String modpackId) throws IOException {
		Path root = storage.preservationPackDirectory(modpackId);
		validateNoSymbolicLinkDescendants(storage.preservationDirectory(), root, "preservation vault");
		Path manifest = storage.preservationManifest(modpackId);
		if (Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS)) {
			ClientStorageJsons.ClientPreservationVaultFields empty = new ClientStorageJsons.ClientPreservationVaultFields();
			empty.modpackId = modpackId;
			empty.claims = new ArrayList<>();
			return empty;
		}
		validateNoSymbolicLinkDescendants(root, manifest, "preservation manifest");
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
			String expectedId = claimId(parsed.modpackId(), parsed.generationId(), parsed.reason(), parsed.sourceRoot(), parsed.originalPath(), parsed.objectHash(), parsed.size());
			if (!expectedId.equals(parsed.claimId())) throw new IOException("Preservation claim identity is invalid");
		}
		List<ClientStorageJsons.ClientPreservationVaultFields.ClaimFields> sorted = new ArrayList<>(fields.claims);
		sorted.sort(CLAIM_ORDER);
		if (!sorted.equals(fields.claims)) throw new IOException("Preservation claims are not deterministically ordered");
		return fields;
	}

	private static void write(ClientStorage storage, String modpackId, ClientStorageJsons.ClientPreservationVaultFields fields) throws IOException {
		Path root = storage.preservationPackDirectory(modpackId);
		validateNoSymbolicLinkDescendants(storage.preservationDirectory(), root, "preservation vault");
		Files.createDirectories(root);
		validateNoSymbolicLinkDescendants(storage.preservationDirectory(), root, "preservation vault");
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
		Status status;
		try {
			root = requireRestorableRoot(Root.valueOf(fields.sourceRoot));
			reason = Reason.valueOf(fields.reason);
			status = Status.valueOf(fields.status);
		} catch (RuntimeException e) {
			throw new IOException("Preservation claim classification is invalid", e);
		}
		String hash = requireHash(fields.objectHash, "preservation object hash");
		if (fields.size < 0) throw new IOException("Preservation claim size is invalid");
		String pack = ModpackId.requireValid(fields.modpackId);
		String generation = requireOptionalHash(fields.generationId, "preservation generation ID");
		Instant time;
		try {
			time = requireInstant(Instant.parse(fields.preservedAt));
		} catch (RuntimeException e) {
			throw new IOException("Preservation timestamp is invalid", e);
		}
		return new Claim(id, path, root, hash, fields.size, pack, generation, reason, time, status);
	}

	private static String claimId(String modpackId, String generationId, Reason reason, Root sourceRoot, String path, String hash, long size) {
		return HashUtils.sha1("automodpack-preservation-v1\nmodpack=" + modpackId + "\ngeneration=" + generationId + "\nreason=" + reason.name() + "\nroot=" + sourceRoot.name()
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
		validateNoSymbolicLinkDescendants(constrainedRoot, source, "preservation source");
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Preservation source is not a regular file: " + source);
	}

	private static void repairObjectFromSource(ClientStorage storage, Path source, Path object, String hash, long size) throws IOException {
		Path sourceRoot = source.startsWith(storage.gameDirectory()) ? storage.gameDirectory() : storage.clientDirectory();
		validateNoSymbolicLinkDescendants(sourceRoot, source, "preservation source");
		if (!FileIntegrity.matches(source, size, hash)) throw new IOException("Preserved object is corrupt and its source is unavailable: " + hash);
		VerifiedFileTransfer.copyAtomicImmutable(source, object, size, hash);
	}

	private static Path object(ClientStorage storage, String hash) throws IOException {
		Path root = storage.objectsDirectory().toAbsolutePath().normalize();
		Path object = root.resolve(requireHash(hash, "preservation object hash")).normalize();
		validateNoSymbolicLinkDescendants(root, object, "preservation object");
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

	private static void validateNoSymbolicLinkDescendants(Path constrainedRoot, Path target, String description) throws IOException {
		Path root = constrainedRoot.toAbsolutePath().normalize();
		Path resolved = target.toAbsolutePath().normalize();
		if (!resolved.startsWith(root)) throw new IOException(description + " escapes its root");
		Path current = root;
		if (Files.isSymbolicLink(current)) throw new IOException(description + " root is a symbolic link: " + current);
		for (Path component : root.relativize(resolved)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) throw new IOException(description + " contains a symbolic link: " + current);
		}
	}
}
