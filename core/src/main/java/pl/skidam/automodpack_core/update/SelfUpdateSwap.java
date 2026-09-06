package pl.skidam.automodpack_core.update;

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
		StorageJsons.SelfUpdateFields record = validated(currentPath, targetPath, targetSha1, targetSize, currentSha1);
		ConfigTools.writeAtomic(gameDirectory.resolve(SELF_UPDATE_FILE).normalize(), record);
		recover(gameDirectory, dataLocation);
	}

	/** Applies and clears a pending swap; a no-op when none is pending. */
	public static void recover(Path gameDirectory, DataRootResolver.Location dataLocation) throws IOException {
		Path recordFile = gameDirectory.resolve(SELF_UPDATE_FILE).normalize();
		if (!Files.exists(recordFile, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isSymbolicLink(recordFile) || !Files.isRegularFile(recordFile, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Pending self-update record is not a regular file");
		StorageJsons.SelfUpdateFields record = ConfigTools.read(recordFile, StorageJsons.SelfUpdateFields.class)
				.orElseThrow(() -> new IOException("Pending self-update record is empty"));
		StorageJsons.SelfUpdateFields swap = validated(record.currentPath, record.targetPath, record.targetSha1, record.targetSize, record.currentSha1);

		Path target = LogicalPath.resolve(gameDirectory, swap.targetPath);
		if (!FileIntegrity.matches(target, swap.targetSize, swap.targetSha1)) {
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Self-update target changed after planning: " + target);
			Path source = dataLocation.layout().objectFile(swap.targetSha1);
			if (!FileIntegrity.matches(source, swap.targetSize, swap.targetSha1)) throw new IOException("Self-update CAS object is missing or corrupt: " + source);
			Files.createDirectories(target.getParent());
			VerifiedFileTransfer.copyAtomic(source, target, swap.targetSize, swap.targetSha1);
		}

		Path current = LogicalPath.resolve(gameDirectory, swap.currentPath);
		if (!swap.currentPath.equals(swap.targetPath) && Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
			String liveHash = FileIntegrity.identityHash(current, null);
			if (liveHash == null || !liveHash.equalsIgnoreCase(swap.currentSha1)) throw new IOException("Superseded AutoModpack jar changed after planning: " + current);
			Files.delete(current);
			FileTrees.pruneEmptyAncestors(current, gameDirectory);
		}

		Files.deleteIfExists(recordFile);
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
