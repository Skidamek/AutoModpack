package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.candidate.CandidateBuildException;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidateScanner;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

public class ModpackExecutor {
	private final ThreadPoolExecutor creationExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
			Math.max(1, Runtime.getRuntime().availableProcessors() * 2), new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build());
	private final Object generationLock = new Object();
	private final AtomicBoolean generating = new AtomicBoolean();
	private final Path serverRoot;
	private final Path groupRoot;
	private final Path generationRoot;
	private final GenerationStore generationStore;
	private final ModpackCandidateScanner scanner = new ModpackCandidateScanner();
	private CompletableFuture<Jsons.CompleteModpackContentFields> refreshInFlight;

	public ModpackExecutor() {
		this(SmartFileUtils.CWD, hostModpackDir, hostGenerationsDir);
	}

	public ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot) {
		this.serverRoot = serverRoot.toAbsolutePath().normalize();
		this.groupRoot = groupRoot.toAbsolutePath().normalize();
		this.generationRoot = generationRoot.toAbsolutePath().normalize();
		this.generationStore = new GenerationStore(this.generationRoot);
	}

	public GenerationResult generateNew() {
		synchronized (generationLock) {
			if (!generating.compareAndSet(false, true))
				return failed(new IllegalStateException("A modpack generation is already in progress"));
			try {
				Optional<GenerationStore.CurrentSnapshot> previous = generationStore.loadCurrent();
				validateConfiguration();
				prepareDirectories();
				String modpackId = previous.map(snapshot -> ModpackId.requireValid(snapshot.record().manifest().modpackId())).orElseGet(ModpackId::generate);
				GroupManifest previousManifest = previous.map(snapshot -> snapshot.record().manifest()).orElse(null);
				Set<Jsons.ModpackContentFields.FileToDelete> deletions = deletionMetadata(previousManifest);
				ModpackCandidateScanner.Request request = new ModpackCandidateScanner.Request(modpackId, serverConfig.modpackName, AM_VERSION, LOADER,
						LOADER_VERSION, MC_VERSION, serverRoot, groupRoot, serverConfig.groups, serverConfig.selectionTags, deletions,
						serverConfig.autoExcludeUnnecessaryFiles, serverConfig.autoExcludeServerSideMods, generationRoot.resolve(hostGenerationStagingDir.getFileName()), creationExecutor);
				GenerationStore.Publication publication;
				try (ModpackCandidate candidate = scanner.scan(request)) {
					publication = generationStore.publish(candidate, previous, "");
					LOGGER.info("Modpack generation {} with {} groups and {} unique objects", publication.status(), candidate.manifest().groups().size(), candidate.objects().size());
				}
				replaceHosting(publication.hostingPaths());
				cleanupLegacyCatalogue();
				return new GenerationResult(publication.status() == GenerationStore.PublicationStatus.PUBLISHED ? GenerationStatus.PUBLISHED : GenerationStatus.NO_CHANGES,
						publication.record(), null);
			} catch (Exception e) {
				LOGGER.error("Failed to generate modpack generation", e);
				return failed(e);
			} finally {
				generating.set(false);
			}
		}
	}

	// Transitional until G3: client refresh can still trigger source generation.
	public CompletableFuture<Jsons.CompleteModpackContentFields> regenerateFullManifest() {
		synchronized (generationLock) {
			if (refreshInFlight != null && !refreshInFlight.isDone()) return refreshInFlight;
			refreshInFlight = CompletableFuture.supplyAsync(() -> {
				GenerationResult result = generateNew();
				if (!result.succeeded()) throw new CompletionException(new IOException("Failed to regenerate modpack", result.failure()));
				return result.current().toFields();
			});
			return refreshInFlight;
		}
	}

	public GenerationResult loadLast() {
		synchronized (generationLock) {
			if (!generating.compareAndSet(false, true)) return failed(new IllegalStateException("A modpack generation is already in progress"));
			try {
				GenerationStore.CurrentSnapshot current = generationStore.loadCurrent().orElseThrow(() -> new IOException("No current generation pointer exists"));
				replaceHosting(current.hostingPaths());
				cleanupLegacyCatalogue();
				return new GenerationResult(GenerationStatus.NO_CHANGES, current.record(), null);
			} catch (Exception e) {
				LOGGER.error("Failed to validate and load the current modpack generation", e);
				return failed(e);
			} finally {
				generating.set(false);
			}
		}
	}

	public Optional<GenerationRecord> currentRecord() throws IOException {
		return generationStore.loadCurrent().map(GenerationStore.CurrentSnapshot::record);
	}

	private void replaceHosting(Map<String, Path> paths) {
		if (hostServer != null) hostServer.replacePaths(paths);
	}

	private void cleanupLegacyCatalogue() {
		Path legacyCatalogue = groupRoot.resolve(modpackContentFileName).normalize();
		try {
			if (Files.deleteIfExists(legacyCatalogue)) LOGGER.debug("Removed stale generated catalogue {}", legacyCatalogue);
		} catch (IOException e) {
			LOGGER.warn("Failed to remove stale generated catalogue {}", legacyCatalogue, e);
		}
	}

	private static GenerationResult failed(Throwable failure) {
		return new GenerationResult(GenerationStatus.FAILED, null, failure);
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
			if (groupDirectory.startsWith(generationRoot) || generationRoot.startsWith(groupDirectory))
				throw new CandidateBuildException("Group directory overlaps managed generation store: " + groupId);
			groupDirectories.put(groupId, groupDirectory);
		}
		Files.createDirectories(groupRoot);
		Files.createDirectories(generationRoot);
		Files.createDirectories(generationRoot.resolve(hostGenerationObjectsDir.getFileName()));
		Files.createDirectories(generationRoot.resolve(hostGenerationStagingDir.getFileName()));
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
