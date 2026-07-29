package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.candidate.*;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public class ModpackExecutor {
	private final ThreadPoolExecutor creationExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
			Math.max(1, Runtime.getRuntime().availableProcessors() * 2), new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build());
	private final Object generationLock = new Object();
	private final AtomicBoolean generating = new AtomicBoolean();
	private final Path serverRoot;
	private final Path groupRoot;
	private final Path manifestPath;
	private final ModpackCandidateScanner scanner = new ModpackCandidateScanner();
	private CompletableFuture<Jsons.CompleteModpackContentFields> refreshInFlight;

	public ModpackExecutor() {
		this(SmartFileUtils.CWD, hostModpackDir, hostModpackContentFile);
	}

	public ModpackExecutor(Path serverRoot, Path groupRoot, Path manifestPath) {
		this.serverRoot = serverRoot.toAbsolutePath().normalize();
		this.groupRoot = groupRoot.toAbsolutePath().normalize();
		this.manifestPath = manifestPath.toAbsolutePath().normalize();
	}

	public boolean generateNew() {
		synchronized (generationLock) {
			if (!generating.compareAndSet(false, true)) return false;
			try {
				validateConfiguration();
				prepareDirectories();
				GroupManifest previous = ModpackContentTools.readComplete(manifestPath);
				String modpackId = previous == null ? ModpackId.generate() : ModpackId.requireValid(previous.modpackId());
				Set<Jsons.ModpackContentFields.FileToDelete> deletions = deletionMetadata(previous);
				ModpackCandidateScanner.Request request = new ModpackCandidateScanner.Request(modpackId, serverConfig.modpackName, AM_VERSION, LOADER,
						LOADER_VERSION, MC_VERSION, serverRoot, groupRoot, serverConfig.groups, serverConfig.selectionTags, deletions,
						serverConfig.autoExcludeUnnecessaryFiles, serverConfig.autoExcludeServerSideMods, creationExecutor);
				try (FileMetadataCache cache = FileMetadataCache.open(hashCacheDBFile)) {
					ModpackCandidate candidate = scanner.scan(request, cache);
					new LegacyCandidatePublisher(manifestPath, hostServer).publish(candidate);
					LOGGER.info("Modpack candidate published with {} groups and {} unique objects", candidate.manifest().groups().size(), candidate.hostedPaths().size());
				}
				return true;
			} catch (Exception e) {
				LOGGER.error("Failed to generate modpack candidate", e);
				return false;
			} finally {
				generating.set(false);
			}
		}
	}

	public CompletableFuture<Jsons.CompleteModpackContentFields> regenerateFullManifest() {
		synchronized (generationLock) {
			if (refreshInFlight != null && !refreshInFlight.isDone()) return refreshInFlight;
			refreshInFlight = CompletableFuture.supplyAsync(() -> {
				if (!generateNew()) throw new CompletionException(new IOException("Failed to regenerate modpack"));
				GroupManifest manifest = ModpackContentTools.readComplete(manifestPath);
				if (manifest == null) throw new CompletionException(new IOException("Regenerated catalogue is unavailable"));
				return manifest.toFields();
			});
			return refreshInFlight;
		}
	}

	public boolean loadLast() {
		synchronized (generationLock) {
			if (!generating.compareAndSet(false, true)) return false;
			try {
				GroupManifest manifest = ModpackContentTools.readComplete(manifestPath);
				if (manifest == null) return false;
				Map<String, Path> hostedPaths = resolvePublishedSources(manifest);
				if (hostServer != null) hostServer.replacePaths(hostedPaths);
				return true;
			} catch (Exception e) {
				LOGGER.error("Failed to validate and load the last published modpack catalogue", e);
				return false;
			} finally {
				generating.set(false);
			}
		}
	}

	private Map<String, Path> resolvePublishedSources(GroupManifest manifest) throws CandidateBuildException {
		validateConfiguration();
		Map<String, Path> paths = new TreeMap<>();
		for (var groupEntry : manifest.groups().entrySet()) {
			String groupId = groupEntry.getKey();
			Jsons.GroupDeclaration declaration = serverConfig.groups.get(groupId);
			if (declaration == null) throw new CandidateBuildException("Published group is absent from server config: " + groupId);
			PathRuleSet syncedRules = new PathRuleSet(declaration.syncedFiles);
			for (var fileEntry : groupEntry.getValue().files().entrySet()) {
				String logicalPath = fileEntry.getKey();
				GroupManifest.GroupFile file = fileEntry.getValue();
				Path groupSource = groupRoot.resolve(groupId).resolve(logicalPath).normalize();
				Path syncedSource = serverRoot.resolve(logicalPath).normalize();
				Path selected = null;
				if (isValidHostedSource(groupRoot, groupSource, file)) selected = groupSource;
				else
					if (syncedRules.evaluate(logicalPath).included()
							&& isValidHostedSource(serverRoot, syncedSource, file))
						selected = syncedSource;
				if (selected == null) throw new CandidateBuildException("Published object cannot be reproduced for group '" + groupId + "' path '" + logicalPath + "'");
				paths.putIfAbsent(file.sha1().toLowerCase(Locale.ROOT), selected);
			}
		}
		return paths;
	}

	private static boolean isValidHostedSource(Path root, Path source, GroupManifest.GroupFile file) {
		if (!source.startsWith(root) || Files.isSymbolicLink(root)) return false;
		try {
			Path current = root;
			for (Path component : root.relativize(source)) {
				current = current.resolve(component);
				if (Files.isSymbolicLink(current)) return false;
			}
			return SmartFileUtils.isValidFile(source, file.size(), file.sha1());
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static void validateConfiguration() throws CandidateBuildException {
		if (serverConfig == null || serverConfig.groups == null || serverConfig.groups.isEmpty())
			throw new CandidateBuildException("Server group configuration is missing");
		for (var entry : serverConfig.groups.entrySet()) {
			try {
				GroupManifestValidator.requireIdentifier(entry.getKey());
			} catch (IllegalArgumentException e) {
				throw new CandidateBuildException(e.getMessage(), e);
			}
			if (entry.getValue() == null) throw new CandidateBuildException("Group '" + entry.getKey() + "' has no declaration");
		}
	}

	private Set<Jsons.ModpackContentFields.FileToDelete> deletionMetadata(GroupManifest previous) {
		Map<String, Jsons.ModpackContentFields.FileToDelete> previousByPath = new HashMap<>();
		if (previous != null)
			for (GroupManifest.DeletionRequest deletion : previous.nonModpackFilesToDelete())
				previousByPath.put(deletion.file(), new Jsons.ModpackContentFields.FileToDelete(deletion.file(), deletion.sha1(), deletion.timestamp()));
		Set<Jsons.ModpackContentFields.FileToDelete> result = new LinkedHashSet<>();
		if (serverConfig.nonModpackFilesToDelete == null) return result;
		for (var entry : new TreeMap<>(serverConfig.nonModpackFilesToDelete).entrySet()) {
			String path = LogicalPath.normalize(entry.getKey());
			Jsons.ModpackContentFields.FileToDelete old = previousByPath.get(path);
			String timestamp = old != null && old.sha1.equalsIgnoreCase(entry.getValue()) ? old.timestamp : String.valueOf(System.currentTimeMillis());
			result.add(new Jsons.ModpackContentFields.FileToDelete(path, entry.getValue(), timestamp));
		}
		return result;
	}

	private void prepareDirectories() throws IOException, CandidateBuildException {
		Map<String, Path> groupDirectories = new TreeMap<>();
		for (String groupId : serverConfig.groups.keySet()) {
			Path groupDirectory = groupRoot.resolve(groupId).normalize();
			if (!groupDirectory.startsWith(groupRoot)) throw new CandidateBuildException("Group directory escapes host-modpack: " + groupId);
			groupDirectories.put(groupId, groupDirectory);
		}
		Files.createDirectories(groupRoot);
		for (Path groupDirectory : groupDirectories.values()) Files.createDirectories(groupDirectory);
		Path main = groupDirectories.get("main");
		if (main == null) return;
		Files.createDirectories(main.resolve("mods"));
		Files.createDirectories(main.resolve("config"));
		Files.createDirectories(main.resolve("shaderpacks"));
		Files.createDirectories(main.resolve("resourcepacks"));
	}

	public boolean isGenerating() {
		return generating.get();
	}

	public void stop() {
		creationExecutor.shutdown();
	}
}
