package pl.skidam.automodpack_core.modpack.candidate;

import static pl.skidam.automodpack_core.Constants.MOD_ID;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public final class StableSourceReader {
	private static final int MAX_ATTEMPTS = 3;

	public Observation read(CandidateSource source, boolean autoExcludeUnnecessary, boolean autoExcludeServerMods, FileMetadataCache cache)
			throws CandidateBuildException {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				BasicFileAttributes before = attributes(source.sourcePath());
				Exclusion exclusion = expectedExclusion(source, before, autoExcludeUnnecessary, autoExcludeServerMods);
				if (exclusion != null) return new Observation(null, exclusion);

				String type = fileType(source.sourcePath(), source.logicalPath());
				String sha1 = cache == null ? HashUtils.getHash(source.sourcePath()) : cache.getHashOrNullWithAttributes(source.sourcePath(), before);
				if (sha1 == null) throw new IOException("SHA-1 calculation returned null");
				String murmur = null;
				if (type.equals("mod") || type.equals("shader") || type.equals("resourcepack")) murmur = HashUtils.getCurseforgeMurmurHash(source.sourcePath());
				BasicFileAttributes after = attributes(source.sourcePath());
				if (!stable(before, after)) {
					if (attempt == MAX_ATTEMPTS) throw new CandidateBuildException("Source remained unstable after " + MAX_ATTEMPTS + " attempts: " + source.sourcePath());
					continue;
				}
				return new Observation(new GroupManifest.GroupFile(after.size(), type, false, false, false, sha1, murmur), null);
			} catch (CandidateBuildException e) {
				throw e;
			} catch (Exception e) {
				throw new CandidateBuildException("Failed to inspect stable source " + source.sourcePath(), e);
			}
		}
		throw new CandidateBuildException("Failed to inspect source " + source.sourcePath());
	}

	private static BasicFileAttributes attributes(Path path) throws IOException {
		if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not allowed");
		BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile()) throw new IOException("Source is not a regular file");
		return attributes;
	}

	private static Exclusion expectedExclusion(CandidateSource source, BasicFileAttributes attributes, boolean autoExcludeUnnecessary,
			boolean autoExcludeServerMods) {
		String logicalPath = source.logicalPath();
		String fileName = source.sourcePath().getFileName().toString();
		if (logicalPath.equals("automodpack") || logicalPath.startsWith("automodpack/"))
			return new Exclusion(ExcludedCandidate.Reason.INTERNAL_FILE, "AutoModpack internal content is never published");
		if (autoExcludeUnnecessary) {
			if (attributes.size() == 0) return new Exclusion(ExcludedCandidate.Reason.EMPTY_FILE, "empty file");
			for (Path component : Path.of(logicalPath))
				if (component.toString().startsWith("."))
					return new Exclusion(ExcludedCandidate.Reason.HIDDEN_FILE, "hidden file or directory");
			if (logicalPath.endsWith(".tmp")) return new Exclusion(ExcludedCandidate.Reason.TEMPORARY_FILE, "temporary file");
			if (logicalPath.endsWith(".disabled")) return new Exclusion(ExcludedCandidate.Reason.DISABLED_FILE, "disabled file");
			if (logicalPath.endsWith(".bak")) return new Exclusion(ExcludedCandidate.Reason.BACKUP_FILE, "backup file");
		}
		if (FileInspection.isMod(source.sourcePath())) {
			if (autoExcludeServerMods && LoaderManagerService.EnvironmentType.SERVER.equals(FileInspection.getModEnvironment(source.sourcePath())))
				return new Exclusion(ExcludedCandidate.Reason.SERVER_SIDE_MOD, "detected as a server-side mod");
			String modId = FileInspection.getModID(source.sourcePath());
			if (MOD_ID.equals(modId) || (MOD_ID + "_bootstrap").equals(modId) || (MOD_ID + "-bootstrap").equals(modId) || (MOD_ID + "_mod").equals(modId))
				return new Exclusion(ExcludedCandidate.Reason.AUTOMODPACK_FILE, "AutoModpack cannot publish itself");
		}
		return null;
	}

	private static String fileType(Path source, String logicalPath) {
		if (FileInspection.isMod(source)) return "mod";
		if (logicalPath.startsWith("config/")) return "config";
		if (logicalPath.startsWith("shaderpacks/")) return "shader";
		if (logicalPath.startsWith("resourcepacks/")) return "resourcepack";
		if (logicalPath.equals("options.txt")) return "mc_options";
		return "other";
	}

	private static boolean stable(BasicFileAttributes before, BasicFileAttributes after) {
		return before.isRegularFile() == after.isRegularFile() && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
				&& (before.fileKey() == null || after.fileKey() == null || Objects.equals(before.fileKey(), after.fileKey()));
	}

	public record Observation(GroupManifest.GroupFile file, Exclusion exclusion) {}

	public record Exclusion(ExcludedCandidate.Reason reason, String message) {}
}
