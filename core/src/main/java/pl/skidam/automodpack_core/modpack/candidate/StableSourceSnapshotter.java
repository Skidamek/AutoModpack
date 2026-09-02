package pl.skidam.automodpack_core.modpack.candidate;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.MOD_ID;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.ModpackContentType;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;

public final class StableSourceSnapshotter {
	private final CopyOperation copyOperation;

	public StableSourceSnapshotter() {
		this(HashUtils::copyAndSha1);
	}

	StableSourceSnapshotter(CopyOperation copyOperation) {
		this.copyOperation = Objects.requireNonNull(copyOperation);
	}

	public Snapshot snapshot(CandidateSource source, boolean autoExcludeUnnecessary, boolean autoExcludeServerMods, Path stagingDirectory)
			throws CandidateBuildException {
		return snapshot(source, autoExcludeUnnecessary, autoExcludeServerMods, stagingDirectory, null, null, null, true);
	}

	public Snapshot snapshot(CandidateSource source, boolean autoExcludeUnnecessary, boolean autoExcludeServerMods, Path stagingDirectory,
			FileMetadataCache fileMetadataCache, ModFileCache modFileCache, Path objectStoreDirectory) throws CandidateBuildException {
		return snapshot(source, autoExcludeUnnecessary, autoExcludeServerMods, stagingDirectory, fileMetadataCache, modFileCache, objectStoreDirectory, true);
	}

	public Snapshot snapshot(CandidateSource source, boolean autoExcludeUnnecessary, boolean autoExcludeServerMods, Path stagingDirectory,
			FileMetadataCache fileMetadataCache, ModFileCache modFileCache, Path objectStoreDirectory, boolean materializeMissing) throws CandidateBuildException {
		Path staged = null;
		try {
			BasicFileAttributes before = attributes(source.sourcePath());
			FileMetadataCache.FileFingerprint beforeFingerprint = FileMetadataCache.fingerprint(source.sourcePath(), before);
			Exclusion exclusion = expectedPathExclusion(source, before, autoExcludeUnnecessary);
			if (exclusion != null) return new Snapshot(null, exclusion, null);

			String sha1 = fileMetadataCache != null ? fileMetadataCache.getOrComputeHashWithAttributes(source.sourcePath(), before) : HashUtils.getHash(source.sourcePath());
			if (sha1 == null) throw new IOException("SHA-1 calculation returned null");
			if (!beforeFingerprint.equals(FileMetadataCache.fingerprint(source.sourcePath(), attributes(source.sourcePath()))))
				throw new CandidateBuildException("Source changed while being snapshotted: " + source.sourcePath());

			FileInspection.Mod mod = modFileCache == null ? null : modFileCache.getModOrNull(source.sourcePath(), fileMetadataCache);
			exclusion = expectedContentExclusion(source.sourcePath(), autoExcludeServerMods, mod);
			if (exclusion != null) return new Snapshot(null, exclusion, null);
			String type = fileType(source.sourcePath(), source.logicalPath(), mod);
			String murmur = null;
			if (ModpackContentType.isSourceFetchable(type)) murmur = fileMetadataCache != null ? fileMetadataCache.getOrComputeMurmur(source.sourcePath()) : HashUtils.getCurseforgeMurmurHash(source.sourcePath());
			if (!beforeFingerprint.equals(FileMetadataCache.fingerprint(source.sourcePath(), attributes(source.sourcePath()))))
				throw new CandidateBuildException("Source changed while being snapshotted: " + source.sourcePath());
			GroupManifest.GroupFile file = new GroupManifest.GroupFile(before.size(), type, false, sha1, murmur);
			if (!materializeMissing) return new Snapshot(file, null, null);
			if (trustedObject(objectStoreDirectory, sha1, before.size(), fileMetadataCache)) return new Snapshot(file, null, null);

			FileTrees.createManagedDirectory(stagingDirectory, "staging directory");
			staged = Files.createTempFile(stagingDirectory, "snapshot-", stagingSuffix(source.sourcePath()));
			String copiedSha1 = copyOperation.copy(source.sourcePath(), staged);
			FileTrees.forceFile(staged);
			if (!beforeFingerprint.equals(FileMetadataCache.fingerprint(source.sourcePath(), attributes(source.sourcePath()))))
				throw new CandidateBuildException("Source changed while being snapshotted: " + source.sourcePath());
			long size = Files.size(staged);
			if (size != before.size()) throw new IOException("Staged snapshot size does not match stable source size: " + source.sourcePath());
			if (copiedSha1 == null || !sha1.equalsIgnoreCase(copiedSha1)) throw new IOException("Staged snapshot SHA-1 does not match source identity: " + source.sourcePath());
			return new Snapshot(file, null, new StagedObject(sha1, size, staged));
		} catch (CandidateBuildException e) {
			delete(staged, e);
			throw e;
		} catch (Exception e) {
			CandidateBuildException failure = new CandidateBuildException("Failed to snapshot stable source " + source.sourcePath(), e);
			delete(staged, failure);
			throw failure;
		}
	}

	private static boolean trustedObject(Path objectStoreDirectory, String sha1, long size, FileMetadataCache cache) {
		if (objectStoreDirectory == null) return false;
		Path object;
		try {
			object = DataRootResolver.objectFile(objectStoreDirectory, sha1);
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (!FileIntegrity.matchesNamed(object, size, sha1, cache)) {
			if (Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) LOGGER.warn("Immutable object {} no longer matches its Git-stat tripwire; restaging from source", object);
			return false;
		}
		return true;
	}

	private static BasicFileAttributes attributes(Path path) throws IOException {
		if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not allowed");
		BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile()) throw new IOException("Source is not a regular file");
		return attributes;
	}

	private static Exclusion expectedPathExclusion(CandidateSource source, BasicFileAttributes attributes, boolean autoExcludeUnnecessary) {
		String logicalPath = source.logicalPath();
		if (logicalPath.equals("automodpack") || logicalPath.startsWith("automodpack/"))
			return new Exclusion(ExcludedCandidate.Reason.INTERNAL_FILE, "AutoModpack internal content is never published");
		if (!autoExcludeUnnecessary) return null;
		if (attributes.size() == 0) return new Exclusion(ExcludedCandidate.Reason.EMPTY_FILE, "empty file");
		for (Path component : Path.of(logicalPath))
			if (component.toString().startsWith(".")) return new Exclusion(ExcludedCandidate.Reason.HIDDEN_FILE, "hidden file or directory");
		if (logicalPath.endsWith(".tmp")) return new Exclusion(ExcludedCandidate.Reason.TEMPORARY_FILE, "temporary file");
		if (logicalPath.endsWith(".disabled")) return new Exclusion(ExcludedCandidate.Reason.DISABLED_FILE, "disabled file");
		if (logicalPath.endsWith(".bak")) return new Exclusion(ExcludedCandidate.Reason.BACKUP_FILE, "backup file");
		return null;
	}

	private static Exclusion expectedContentExclusion(Path source, boolean autoExcludeServerMods, FileInspection.Mod cachedMod) {
		if (cachedMod == null && !FileInspection.isMod(source)) return null;
		if (autoExcludeServerMods && LoaderManagerService.EnvironmentType.SERVER.equals(FileInspection.getModEnvironment(source)))
			return new Exclusion(ExcludedCandidate.Reason.SERVER_SIDE_MOD, "detected as a server-side mod");
		String modId = cachedMod != null && cachedMod.id() != null ? cachedMod.id() : FileInspection.getModID(source);
		if (MOD_ID.equals(modId) || (MOD_ID + "_bootstrap").equals(modId) || (MOD_ID + "-bootstrap").equals(modId)
				|| (MOD_ID + "_mod").equals(modId))
			return new Exclusion(ExcludedCandidate.Reason.AUTOMODPACK_FILE, "AutoModpack cannot publish itself");
		return null;
	}

	private static String fileType(Path source, String logicalPath, FileInspection.Mod cachedMod) {
		if (cachedMod != null || FileInspection.isMod(source)) return ModpackContentType.MOD;
		return ModpackPathPolicy.typeForPath(logicalPath);
	}

	private static String stagingSuffix(Path source) {
		String name = source.getFileName().toString();
		return JarUtils.hasJarExtension(name) ? ".jar" : ".staged";
	}

	private static void delete(Path path, CandidateBuildException failure) {
		if (path == null) return;
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			failure.addSuppressed(e);
		}
	}

	@FunctionalInterface
	interface CopyOperation {
		String copy(Path source, Path staged) throws IOException;
	}

	public record Snapshot(GroupManifest.GroupFile file, Exclusion exclusion, StagedObject object) {}

	public record Exclusion(ExcludedCandidate.Reason reason, String message) {}
}
