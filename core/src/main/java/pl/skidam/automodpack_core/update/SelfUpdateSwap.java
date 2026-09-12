package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.storage.StoragePaths.SELF_UPDATE_FILE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

/**
 * The durable swap of AutoModpack's own jar in the instance's mods directory.
 *
 * <p>
 * This is instance-level state, not client state: both a client and a dedicated server run their
 * real jars from {@code mods/}, can crash mid-swap, and can hold the old jar locked on Windows until
 * the process exits. The record lives at the top of the automodpack tree, is recovered at the very
 * start of every boot before any role machinery wakes up, and is applied idempotently: install the
 * target jar from the shared CAS, then delete the superseded jar.
 * </p>
 */
public final class SelfUpdateSwap {
	private SelfUpdateSwap() {}

	public static void commit(Path gameDirectory, DataRootResolver.Location dataLocation, String currentPath, String targetPath, String targetSha1, long targetSize, String currentSha1)
			throws IOException {
		StorageJsons.SelfUpdateFields swap = validated(currentPath, targetPath, targetSha1, targetSize, currentSha1);
		ConfigTools.writeAtomic(gameDirectory.resolve(SELF_UPDATE_FILE).normalize(), swap);
		// Strict on purpose: a swap planned against bytes that changed since staging fails this update loudly here,
		// instead of surfacing as a skipped boot recovery later.
		installTarget(gameDirectory, dataLocation, swap);
		retireSupersededJar(gameDirectory, swap);
		Files.deleteIfExists(gameDirectory.resolve(SELF_UPDATE_FILE).normalize());
	}

	/**
	 * Applies and clears a pending swap; a no-op when none is pending. A swap that cannot be understood or whose
	 * target bytes are gone is set aside as evidence and skipped, so the instance keeps running its current jar and
	 * re-running the update is the whole recovery - a stale record must never loop the boot. A failure after the
	 * target jar is already installed still propagates: the next boot's retry is what completes the swap.
	 */
	public static void recover(Path gameDirectory, DataRootResolver.Location dataLocation) throws IOException {
		Path recordFile = gameDirectory.resolve(SELF_UPDATE_FILE).normalize();
		StorageJsons.SelfUpdateFields swap;
		try {
			swap = ConfigTools.readState(recordFile, StorageJsons.SelfUpdateFields.class, "Pending self-update record", fields -> {
				try {
					return validated(fields.currentPath, fields.targetPath, fields.targetSha1, fields.targetSize, fields.currentSha1);
				} catch (IOException e) {
					throw new IllegalArgumentException("Pending self-update record is invalid", e);
				}
			}).orElse(null);
		} catch (IOException | RuntimeException e) {
			LOGGER.error("The pending self-update record {} could not be read, so the swap was skipped and AutoModpack stays on its current jar", recordFile, e);
			return;
		}
		if (swap == null) return;
		try {
			installTarget(gameDirectory, dataLocation, swap);
		} catch (IOException | RuntimeException e) {
			DurableFiles.setAside(recordFile, "Pending self-update record", e);
			LOGGER.error("The pending self-update of {} could not be applied, so AutoModpack stays on its current jar; re-run the update to retry", swap.targetPath, e);
			return;
		}
		retireSupersededJar(gameDirectory, swap);
		Files.deleteIfExists(recordFile);
	}

	/** Installs the recorded target jar from the shared CAS; a no-op when the target already matches. Nothing is applied before this verifies. */
	private static void installTarget(Path gameDirectory, DataRootResolver.Location dataLocation, StorageJsons.SelfUpdateFields swap) throws IOException {
		Path target = LogicalPath.resolve(gameDirectory, swap.targetPath);
		if (FileIntegrity.matches(target, swap.targetSize, swap.targetSha1)) return;
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Self-update target changed after planning: " + target);
		Path source = dataLocation.layout().objectFile(swap.targetSha1);
		if (!FileIntegrity.matches(source, swap.targetSize, swap.targetSha1)) throw new IOException("Self-update CAS object is missing or corrupt: " + source);
		Files.createDirectories(target.getParent());
		VerifiedFileTransfer.copyAtomic(source, target, swap.targetSize, swap.targetSha1);
	}

	/** Deletes the superseded jar once the target is live; its failures are retryable on the next boot while the record remains. */
	private static void retireSupersededJar(Path gameDirectory, StorageJsons.SelfUpdateFields swap) throws IOException {
		if (swap.currentPath.equals(swap.targetPath)) return;
		Path current = LogicalPath.resolve(gameDirectory, swap.currentPath);
		if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
		String liveHash = FileIntegrity.identityHash(current, null);
		if (liveHash == null || !liveHash.equalsIgnoreCase(swap.currentSha1)) throw new IOException("Superseded AutoModpack jar changed after planning: " + current);
		Files.delete(current);
		FileTrees.pruneEmptyAncestors(current, gameDirectory);
	}

	/** The swap touches exactly AutoModpack's own jar: a direct jar child of the mods directory, never anything else. */
	private static StorageJsons.SelfUpdateFields validated(String currentPath, String targetPath, String targetSha1, long targetSize, String currentSha1) throws IOException {
		StorageJsons.SelfUpdateFields record = new StorageJsons.SelfUpdateFields();
		record.currentPath = requireModsJar(currentPath, "current");
		record.targetPath = requireModsJar(targetPath, "target");
		if (!HashUtils.isSha1(targetSha1)) throw new IOException("Invalid self-update target SHA-1");
		record.targetSha1 = HashUtils.normalizeSha1(targetSha1);
		if (targetSize <= 0) throw new IOException("Invalid self-update target size");
		record.targetSize = targetSize;
		if (!HashUtils.isSha1(currentSha1)) throw new IOException("Invalid self-update current SHA-1");
		record.currentSha1 = HashUtils.normalizeSha1(currentSha1);
		return record;
	}

	private static String requireModsJar(String path, String role) throws IOException {
		String normalized;
		try {
			normalized = LogicalPath.requireCanonical(path);
		} catch (RuntimeException e) {
			throw new IOException("Invalid self-update " + role + " path: " + path, e);
		}
		Path relative = Path.of(normalized);
		if (relative.getNameCount() != 2 || !relative.getName(0).toString().equalsIgnoreCase(ModpackPathPolicy.MODS_ROOT) || !JarUtils.hasJarExtension(relative))
			throw new IOException("Self-update " + role + " must be a direct jar child of the mods directory: " + path);
		return normalized;
	}
}
