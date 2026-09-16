package pl.skidam.automodpack_core.modpack.candidate;

import static pl.skidam.automodpack_core.Constants.MOD_ID;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.ModpackContentType;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;

public final class StableSourceSnapshotter {
	private final CopyOperation copyOperation;

	public StableSourceSnapshotter() {
		this((source, staged) -> Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING));
	}

	StableSourceSnapshotter(CopyOperation copyOperation) {
		this.copyOperation = Objects.requireNonNull(copyOperation);
	}

	public Snapshot snapshot(CandidateSource source, boolean autoExcludeUnnecessary, boolean autoExcludeServerMods, Path stagingDirectory)
			throws CandidateBuildException {
		return snapshot(source, autoExcludeUnnecessary, autoExcludeServerMods, stagingDirectory, null, null, null);
	}

	public Snapshot snapshot(CandidateSource source, boolean autoExcludeUnnecessary, boolean autoExcludeServerMods, Path stagingDirectory,
			FileMetadataCache fileMetadataCache, ModFileCache modFileCache, Path objectStoreDirectory) throws CandidateBuildException {
		Path staged = null;
		try {
			BasicFileAttributes before = attributes(source.sourcePath());
			Exclusion exclusion = expectedPathExclusion(source, before, autoExcludeUnnecessary);
			if (exclusion != null) return new Snapshot(null, exclusion, null);
			Snapshot cached = cachedSnapshot(source, before, autoExcludeServerMods, stagingDirectory, fileMetadataCache, modFileCache, objectStoreDirectory);
			if (cached != null) return cached;

			ensureStagingDirectory(stagingDirectory);
			staged = Files.createTempFile(stagingDirectory, "snapshot-", stagingSuffix(source.sourcePath()));
			copyOperation.copy(source.sourcePath(), staged);
			force(staged);

			exclusion = expectedContentExclusion(staged, autoExcludeServerMods);
			String type = exclusion == null ? fileType(staged, source.logicalPath()) : null;
			String sha1 = exclusion == null ? HashUtils.getHash(staged) : null;
			if (exclusion == null && sha1 == null) throw new IOException("SHA-1 calculation returned null");
			String murmur = null;
			if (exclusion == null && ModpackContentType.isSourceFetchable(type)) murmur = HashUtils.getCurseforgeMurmurHash(staged);
			BasicFileAttributes after = attributes(source.sourcePath());
			if (!stable(before, after)) throw new CandidateBuildException("Source changed while being snapshotted: " + source.sourcePath());
			long size = Files.size(staged);
			if (size != after.size()) throw new IOException("Staged snapshot size does not match stable source size: " + source.sourcePath());
			if (exclusion != null) {
				Files.deleteIfExists(staged);
				staged = null;
				return new Snapshot(null, exclusion, null);
			}
			if (fileMetadataCache != null) fileMetadataCache.overwriteCache(source.sourcePath(), sha1);
			if (fileMetadataCache != null) fileMetadataCache.overwriteCache(staged, sha1);
			return new Snapshot(new GroupManifest.GroupFile(size, type, false, false, sha1, murmur), null,
					new StagedObject(sha1, size, staged));
		} catch (CandidateBuildException e) {
			delete(staged, e);
			throw e;
		} catch (Exception e) {
			CandidateBuildException failure = new CandidateBuildException("Failed to snapshot stable source " + source.sourcePath(), e);
			delete(staged, failure);
			throw failure;
		}
	}

	private Snapshot cachedSnapshot(CandidateSource source, BasicFileAttributes before, boolean autoExcludeServerMods, Path stagingDirectory,
			FileMetadataCache fileMetadataCache, ModFileCache modFileCache, Path objectStoreDirectory) throws IOException, CandidateBuildException {
		if (fileMetadataCache == null || objectStoreDirectory == null) return null;
		String sha1 = fileMetadataCache.getHashOrNullWithAttributes(source.sourcePath(), before);
		if (sha1 == null) return null;
		Path object = objectStoreDirectory.resolve(sha1).normalize();
		if (!object.startsWith(objectStoreDirectory.toAbsolutePath().normalize()) || !Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)
				|| Files.size(object) != before.size() || !sha1.equalsIgnoreCase(fileMetadataCache.getHashOrNull(object)))
			return null;
		ensureStagingDirectory(stagingDirectory);
		Path staged = Files.createTempFile(stagingDirectory, "snapshot-cached-", stagingSuffix(source.sourcePath()));
		Files.deleteIfExists(staged);
		try {
			try {
				Files.createLink(staged, object);
			} catch (UnsupportedOperationException | FileSystemException e) {
				Files.copy(object, staged);
			}
			BasicFileAttributes after = attributes(source.sourcePath());
			if (!stable(before, after)) {
				Files.deleteIfExists(staged);
				return null;
			}
			fileMetadataCache.overwriteCache(staged, sha1);
			FileInspection.Mod mod = modFileCache == null ? null : modFileCache.getModOrNull(staged, fileMetadataCache);
			Exclusion exclusion = expectedContentExclusion(staged, autoExcludeServerMods, mod);
			if (exclusion != null) {
				Files.deleteIfExists(staged);
				return new Snapshot(null, exclusion, null);
			}
			String type = fileType(staged, source.logicalPath(), mod);
			String murmur = ModpackContentType.isSourceFetchable(type) ? HashUtils.getCurseforgeMurmurHash(staged) : null;
			return new Snapshot(new GroupManifest.GroupFile(before.size(), type, false, false, sha1, murmur), null, new StagedObject(sha1, before.size(), staged));
		} catch (IOException e) {
			Files.deleteIfExists(staged);
			throw e;
		} catch (RuntimeException e) {
			Files.deleteIfExists(staged);
			throw e;
		}
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

	private static Exclusion expectedContentExclusion(Path staged, boolean autoExcludeServerMods) {
		return expectedContentExclusion(staged, autoExcludeServerMods, null);
	}

	private static Exclusion expectedContentExclusion(Path staged, boolean autoExcludeServerMods, FileInspection.Mod cachedMod) {
		if (cachedMod == null && !FileInspection.isMod(staged)) return null;
		if (autoExcludeServerMods && LoaderManagerService.EnvironmentType.SERVER.equals(FileInspection.getModEnvironment(staged)))
			return new Exclusion(ExcludedCandidate.Reason.SERVER_SIDE_MOD, "detected as a server-side mod");
		String modId = FileInspection.getModID(staged);
		if (MOD_ID.equals(modId) || (MOD_ID + "_bootstrap").equals(modId) || (MOD_ID + "-bootstrap").equals(modId)
				|| (MOD_ID + "_mod").equals(modId))
			return new Exclusion(ExcludedCandidate.Reason.AUTOMODPACK_FILE, "AutoModpack cannot publish itself");
		return null;
	}

	private static String fileType(Path staged, String logicalPath) {
		return fileType(staged, logicalPath, null);
	}

	private static String fileType(Path staged, String logicalPath, FileInspection.Mod cachedMod) {
		if (cachedMod != null || FileInspection.isMod(staged)) return ModpackContentType.MOD;
		return ModpackPathPolicy.typeForPath(logicalPath);
	}

	private static String stagingSuffix(Path source) {
		String name = source.getFileName().toString();
		return JarUtils.hasJarExtension(name) ? ".jar" : ".staged";
	}

	private static void ensureStagingDirectory(Path stagingDirectory) throws IOException {
		if (Files.isSymbolicLink(stagingDirectory)) throw new IOException("Managed staging directory cannot be a symbolic link: " + stagingDirectory);
		Files.createDirectories(stagingDirectory);
		if (Files.isSymbolicLink(stagingDirectory) || !Files.isDirectory(stagingDirectory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Managed staging directory is not a regular directory: " + stagingDirectory);
	}

	private static void force(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		}
	}

	private static void delete(Path path, CandidateBuildException failure) {
		if (path == null) return;
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			failure.addSuppressed(e);
		}
	}

	private static boolean stable(BasicFileAttributes before, BasicFileAttributes after) {
		return before.isRegularFile() == after.isRegularFile() && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
				&& (before.fileKey() == null || after.fileKey() == null || Objects.equals(before.fileKey(), after.fileKey()));
	}

	@FunctionalInterface
	interface CopyOperation {
		void copy(Path source, Path staged) throws IOException;
	}

	public record Snapshot(GroupManifest.GroupFile file, Exclusion exclusion, StagedObject object) {}

	public record Exclusion(ExcludedCandidate.Reason reason, String message) {}
}
