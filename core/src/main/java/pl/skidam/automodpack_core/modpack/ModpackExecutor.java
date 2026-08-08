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
import pl.skidam.automodpack_core.modpack.generation.GenerationDiff;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryEntry;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.generation.GenerationIdentity;
import pl.skidam.automodpack_core.modpack.generation.GenerationMetadata;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNotes;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;

public class ModpackExecutor {
	private final ThreadPoolExecutor creationExecutor;
	private final AtomicBoolean scanActive = new AtomicBoolean();
	private final AtomicBoolean publicationActive = new AtomicBoolean();
	private final Path serverRoot;
	private final Path groupRoot;
	private final Path generationRoot;
	private final Path patchNotesFile;
	private final GenerationStore generationStore;
	private final CandidateScan candidateScan;

	public ModpackExecutor() {
		this(SmartFileUtils.CWD, hostModpackDir, SmartFileUtils.CWD.resolve(serverDir));
	}

	public ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot) {
		this(serverRoot, groupRoot, generationRoot, new GenerationStore(generationRoot, DataRootResolver.resolve(serverRoot).root().resolve("objects")), new ModpackCandidateScanner()::scan,
				(ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() * 2),
						new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build()));
	}

	ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot, GenerationStore generationStore, CandidateScan candidateScan,
			ThreadPoolExecutor creationExecutor) {
		this.serverRoot = serverRoot.toAbsolutePath().normalize();
		this.groupRoot = groupRoot.toAbsolutePath().normalize();
		this.generationRoot = generationRoot.toAbsolutePath().normalize();
		this.patchNotesFile = this.generationRoot.resolve(serverPatchNotesFile.getFileName()).normalize();
		this.generationStore = Objects.requireNonNull(generationStore);
		this.candidateScan = Objects.requireNonNull(candidateScan);
		this.creationExecutor = Objects.requireNonNull(creationExecutor);
	}

	@FunctionalInterface
	interface CandidateScan {
		ModpackCandidate scan(ModpackCandidateScanner.Request request) throws CandidateBuildException;
	}

	public PreviewResult preview() {
		return preview(null);
	}

	public PreviewResult preview(String inlineNotes) {
		if (!acquire(false)) return new PreviewBusy("Another modpack operation is already in progress");
		try {
			Optional<GenerationStore.CurrentSnapshot> previous = generationStore.loadCurrent();
			try (ModpackCandidate candidate = buildCandidate(previous)) {
				GenerationDiff diff = GenerationDiff.between(previous.map(snapshot -> snapshot.record().manifest()).orElse(null), candidate.manifest());
				String stateDigest = GenerationIdentity.stateDigest(candidate.manifest());
				GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
				return new PreviewReady(candidateState(previous, candidate, stateDigest, diff, notes.source()));
			}
		} catch (Exception e) {
			LOGGER.error("Failed to preview modpack generation", e);
			return new PreviewFailed(e);
		} finally {
			scanActive.set(false);
		}
	}

	public PublishResult publish() {
		return publish(null);
	}

	public PublishResult publish(String inlineNotes) {
		return publishInternal(null, inlineNotes);
	}

	public PublishResult publishIfState(String expectedStateDigest) {
		return publishIfState(expectedStateDigest, null);
	}

	public PublishResult publishIfState(String expectedStateDigest, String inlineNotes) {
		if (expectedStateDigest == null || !expectedStateDigest.matches("[0-9a-f]{40}"))
			return new PublishInvalidGuard("Guard digest must be a canonical 40-character lowercase SHA-1");
		return publishInternal(expectedStateDigest, inlineNotes);
	}

	public RevertResult revert(String targetGenerationId) {
		return revert(targetGenerationId, null);
	}

	public RevertResult revert(String targetGenerationId, String inlineNotes) {
		if (targetGenerationId == null || !targetGenerationId.matches("[0-9a-f]{40}"))
			return new RevertInvalidTarget("Rollback target must be a canonical 40-character lowercase SHA-1");
		if (!acquire(true)) return new RevertBusy("Another modpack operation is already in progress");
		GenerationStore.Publication publication = null;
		try {
			Optional<GenerationStore.CurrentSnapshot> previous = generationStore.loadCurrent();
			GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
			publication = generationStore.publishRevert(targetGenerationId, previous, notes.text());
			List<String> warnings = new ArrayList<>();
			postPublication(publication, notes, warnings);
			return new Reverted(publication.record(), targetGenerationId, warnings);
		} catch (Exception e) {
			if (publication != null) return new Reverted(publication.record(), targetGenerationId, List.of("Revert published, but post-publication cleanup was incomplete"));
			LOGGER.error("Failed to publish modpack revert", e);
			return new RevertFailed(e);
		} finally {
			publicationActive.set(false);
			scanActive.set(false);
		}
	}

	public List<GenerationHistoryEntry> technicalHistory() throws IOException {
		return generationStore.currentHistory();
	}

	public GenerationStore.StorageReport storageReport() throws IOException {
		return generationStore.measureStorage();
	}

	private PublishResult publishInternal(String expectedStateDigest, String inlineNotes) {
		if (!acquire(true)) return new PublishBusy("Another modpack operation is already in progress");
		GenerationStore.Publication publication = null;
		PublishResult committedResult = null;
		CandidateState committedState = null;
		try {
			Optional<GenerationStore.CurrentSnapshot> previous = generationStore.loadCurrent();
			if (expectedStateDigest != null && previous.isEmpty())
				return new PublishGuardUnsupported("A state guard is unavailable before the root generation is published");
			try (ModpackCandidate candidate = buildCandidate(previous)) {
				GenerationRecord parent = previous.map(GenerationStore.CurrentSnapshot::record).orElse(null);
				GenerationDiff diff = GenerationDiff.between(parent == null ? null : parent.manifest(), candidate.manifest());
				String stateDigest = GenerationIdentity.stateDigest(candidate.manifest());
				CandidateSummary summary = CandidateSummary.from(candidate, diff);
				CandidateState candidateState = new CandidateState(previous.map(GenerationStore.CurrentSnapshot::record), stateDigest, diff, summary, Optional.empty());
				if (expectedStateDigest != null && !expectedStateDigest.equals(stateDigest))
					return new PublishGuardMismatch(candidateState, "Fresh candidate state does not match the requested guard");

				if (parent != null && parent.metadata().stateDigest().equals(stateDigest)) {
					publication = generationStore.publish(candidate, previous, "");
					committedState = candidateState;
					committedResult = finishPublication(publication, committedState, null);
					return committedResult;
				}

				GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
				candidateState = candidateState.withPatchNotesSource(notes.source());
				publication = generationStore.publish(candidate, previous, notes.text());
				committedState = candidateState;
				committedResult = finishPublication(publication, committedState, notes);
				return committedResult;
			}
		} catch (Exception e) {
			if (publication != null && publication.status() == GenerationStore.PublicationStatus.PUBLISHED && committedState != null) {
				List<String> warnings = new ArrayList<>();
				if (committedResult instanceof Published published) warnings.addAll(published.warnings());
				warnings.add("Publication committed, but candidate staging cleanup was incomplete");
				return new Published(committedState, publication.record(), warnings);
			}
			LOGGER.error("Failed to publish modpack generation", e);
			return new PublishFailed(e);
		} finally {
			publicationActive.set(false);
			scanActive.set(false);
		}
	}

	private PublishResult finishPublication(GenerationStore.Publication publication, CandidateState state, GenerationPatchNotes.Resolution notes) {
		List<String> warnings = new ArrayList<>();
		postPublication(publication, notes, warnings);
		if (publication.status() == GenerationStore.PublicationStatus.PUBLISHED)
			return new Published(state, publication.record(), warnings);
		return new NoChanges(state, publication.record(), warnings);
	}

	private void postPublication(GenerationStore.Publication publication, GenerationPatchNotes.Resolution notes, List<String> warnings) {
		try {
			replaceHosting(publication.hostingPaths());
		} catch (Exception e) {
			warnings.add("Published generation could not fully replace the active hosting map");
			LOGGER.warn("Published generation is current but hosting replacement failed", e);
		}
		try {
			cleanupLegacyCatalogue();
		} catch (Exception e) {
			warnings.add("Published generation is current but legacy catalogue cleanup failed");
			LOGGER.warn("Published generation is current but legacy catalogue cleanup failed", e);
		}
		if (publication.status() == GenerationStore.PublicationStatus.PUBLISHED && notes != null && notes.isFileSourced()) {
			GenerationPatchNotes.CleanupResult cleanup = notes.consumeIfUnchanged();
			if (!cleanup.warning().isEmpty()) warnings.add(cleanup.warning());
		}
	}

	public LoadResult loadLast() {
		if (!acquire(false)) return new LoadBusy("Another modpack operation is already in progress");
		try {
			GenerationStore.CurrentSnapshot current = generationStore.loadCurrentAndRepair().orElseThrow(() -> new IOException("No current generation pointer exists"));
			try {
				replaceHosting(current.hostingPaths());
				cleanupLegacyCatalogue();
				return new Loaded(current.record());
			} catch (Exception e) {
				LOGGER.error("Failed to activate the current modpack generation", e);
				return new LoadFailed(e);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load the current modpack generation", e);
			return new LoadFailed(e);
		} finally {
			scanActive.set(false);
		}
	}

	public Optional<GenerationRecord> currentRecord() throws IOException {
		return generationStore.loadCurrent().map(GenerationStore.CurrentSnapshot::record);
	}

	private CandidateState candidateState(Optional<GenerationStore.CurrentSnapshot> previous, ModpackCandidate candidate, String stateDigest,
			GenerationDiff diff, GenerationPatchNotes.Source source) {
		return new CandidateState(previous.map(GenerationStore.CurrentSnapshot::record), stateDigest, diff, CandidateSummary.from(candidate, diff), Optional.of(source));
	}

	private ModpackCandidate buildCandidate(Optional<GenerationStore.CurrentSnapshot> previous) throws IOException, CandidateBuildException {
		validateConfiguration();
		prepareDirectories();
		String modpackId = previous.map(snapshot -> ModpackId.requireValid(snapshot.record().manifest().modpackId())).orElseGet(ModpackId::generate);
		Path cacheRoot = generationStore.objectRoot().getParent();
		try (FileMetadataCache fileMetadataCache = FileMetadataCache.open(cacheRoot.resolve("file-metadata"));
				ModFileCache modFileCache = ModFileCache.open(cacheRoot.resolve("mod-metadata"))) {
			ModpackCandidateScanner.Request request = new ModpackCandidateScanner.Request(modpackId, serverConfig.modpackName, AM_VERSION, LOADER,
					LOADER_VERSION, MC_VERSION, serverRoot, groupRoot, serverConfig.groups,
					serverConfig.autoExcludeUnnecessaryFiles, serverConfig.autoExcludeServerSideMods, generationRoot.resolve(serverStagingDir.getFileName()), creationExecutor,
					generationStore.objectRoot(), fileMetadataCache, modFileCache);
			return candidateScan.scan(request);
		}
	}

	private boolean acquire(boolean publication) {
		if (!scanActive.compareAndSet(false, true)) return false;
		if (publication && !publicationActive.compareAndSet(false, true)) {
			scanActive.set(false);
			return false;
		}
		return true;
	}

	private void replaceHosting(GenerationHosting paths) {
		if (hostServer != null) {
			hostServer.replacePaths(paths);
		}
	}

	private void cleanupLegacyCatalogue() throws IOException {
		Path legacyCatalogue = groupRoot.resolve(modpackContentFileName).normalize();
		if (Files.deleteIfExists(legacyCatalogue)) LOGGER.debug("Removed stale generated catalogue");
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
		for (Path groupDirectory : groupDirectories.values()) Files.createDirectories(groupDirectory);
		Path main = groupDirectories.get("main");
		if (main == null) return;
		Files.createDirectories(main.resolve("mods"));
		Files.createDirectories(main.resolve("config"));
		Files.createDirectories(main.resolve("shaderpacks"));
		Files.createDirectories(main.resolve("resourcepacks"));
	}

	public boolean isGenerating() {
		return publicationActive.get();
	}

	public void stop() {
		creationExecutor.shutdown();
	}

	public record CandidateSummary(int groups, int files, int objects, int exclusions, int shadows, GenerationDiff.Summary diff) {
		public CandidateSummary {
			if (groups < 0 || files < 0 || objects < 0 || exclusions < 0 || shadows < 0) throw new IllegalArgumentException("Negative generation summary count");
			diff = Objects.requireNonNull(diff);
		}

		static CandidateSummary from(ModpackCandidate candidate, GenerationDiff diff) {
			int files = candidate.manifest().groups().values().stream().mapToInt(group -> group.files().size()).sum();
			return new CandidateSummary(candidate.manifest().groups().size(), files, candidate.objects().size(), candidate.exclusions().size(), candidate.shadows().size(), diff.summary());
		}

		static CandidateSummary empty() {
			return new CandidateSummary(0, 0, 0, 0, 0, new GenerationDiff.Summary(0, 0, 0, 0, 0));
		}
	}

	public record CandidateState(Optional<GenerationRecord> parent, String candidateStateDigest, GenerationDiff diff, CandidateSummary summary,
			Optional<GenerationPatchNotes.Source> patchNotesSource) {
		public CandidateState {
			parent = parent == null ? Optional.empty() : parent;
			if (candidateStateDigest == null || !candidateStateDigest.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid candidate state digest");
			diff = Objects.requireNonNull(diff);
			summary = Objects.requireNonNull(summary);
			patchNotesSource = patchNotesSource == null ? Optional.empty() : patchNotesSource;
		}

		CandidateState withPatchNotesSource(GenerationPatchNotes.Source source) {
			return new CandidateState(parent, candidateStateDigest, diff, summary, Optional.of(source));
		}
	}

	public sealed interface PreviewResult permits PreviewReady, PreviewBusy, PreviewFailed {}

	public record PreviewReady(CandidateState state) implements PreviewResult {
		public PreviewReady {
			state = Objects.requireNonNull(state);
			if (state.patchNotesSource().isEmpty()) throw new IllegalArgumentException("Preview result requires a resolved patch-note source");
		}
	}

	public record PreviewBusy(String detail) implements PreviewResult {
		public PreviewBusy {
			detail = Objects.requireNonNull(detail);
		}
	}

	public record PreviewFailed(Throwable failure) implements PreviewResult {
		public PreviewFailed {
			failure = Objects.requireNonNull(failure);
		}
	}

	public sealed interface RevertResult permits Reverted, RevertBusy, RevertInvalidTarget, RevertFailed {}

	public record Reverted(GenerationRecord current, String targetGenerationId, List<String> warnings) implements RevertResult {
		public Reverted {
			current = Objects.requireNonNull(current);
			if (targetGenerationId == null || !targetGenerationId.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid rollback target generation ID");
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			if (!targetGenerationId.equals(current.metadata().rollbackTargetGenerationId())) throw new IllegalArgumentException("Revert result target does not match current metadata");
		}
	}

	public record RevertBusy(String detail) implements RevertResult {
		public RevertBusy {
			detail = Objects.requireNonNull(detail);
		}
	}

	public record RevertInvalidTarget(String detail) implements RevertResult {
		public RevertInvalidTarget {
			detail = Objects.requireNonNull(detail);
		}
	}

	public record RevertFailed(Throwable failure) implements RevertResult {
		public RevertFailed {
			failure = Objects.requireNonNull(failure);
		}
	}

	public sealed interface PublishResult permits Published, NoChanges, PublishBusy, PublishInvalidGuard, PublishGuardUnsupported, PublishGuardMismatch, PublishFailed {}

	public record Published(CandidateState state, GenerationRecord current, List<String> warnings) implements PublishResult {
		public Published {
			state = Objects.requireNonNull(state);
			current = Objects.requireNonNull(current);
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			if (!current.metadata().stateDigest().equals(state.candidateStateDigest()))
				throw new IllegalArgumentException("Published generation state does not match the candidate");
			String parentId = state.parent().map(record -> record.metadata().generationId()).orElse(GenerationMetadata.ROOT_PARENT);
			if (!current.metadata().parentGenerationId().equals(parentId))
				throw new IllegalArgumentException("Published generation parent does not match the candidate base");
			if (state.patchNotesSource().isEmpty()) throw new IllegalArgumentException("Published generation requires a resolved patch-note source");
		}
	}

	public record NoChanges(CandidateState state, GenerationRecord current, List<String> warnings) implements PublishResult {
		public NoChanges {
			state = Objects.requireNonNull(state);
			current = Objects.requireNonNull(current);
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			if (!state.diff().isEmpty()) throw new IllegalArgumentException("No-change result must have an empty diff");
			if (!current.metadata().stateDigest().equals(state.candidateStateDigest()))
				throw new IllegalArgumentException("Current generation state does not match the unchanged candidate");
			if (state.parent().isEmpty() || !state.parent().orElseThrow().equals(current))
				throw new IllegalArgumentException("No-change result must retain the current generation as its candidate base");
			if (state.patchNotesSource().isPresent()) throw new IllegalArgumentException("No-change result cannot resolve patch notes");
		}
	}

	public record PublishBusy(String detail) implements PublishResult {
		public PublishBusy {
			detail = Objects.requireNonNull(detail);
		}
	}

	public record PublishInvalidGuard(String detail) implements PublishResult {
		public PublishInvalidGuard {
			detail = Objects.requireNonNull(detail);
		}
	}

	public record PublishGuardUnsupported(String detail) implements PublishResult {
		public PublishGuardUnsupported {
			detail = Objects.requireNonNull(detail);
		}
	}

	public record PublishGuardMismatch(CandidateState state, String detail) implements PublishResult {
		public PublishGuardMismatch {
			state = Objects.requireNonNull(state);
			detail = Objects.requireNonNull(detail);
			if (state.patchNotesSource().isPresent()) throw new IllegalArgumentException("Guard mismatch cannot resolve patch notes");
		}
	}

	public record PublishFailed(Throwable failure) implements PublishResult {
		public PublishFailed {
			failure = Objects.requireNonNull(failure);
		}
	}

	public sealed interface LoadResult permits Loaded, LoadBusy, LoadFailed {}

	public record Loaded(GenerationRecord current) implements LoadResult {
		public Loaded {
			current = Objects.requireNonNull(current);
		}
	}

	public record LoadBusy(String detail) implements LoadResult {
		public LoadBusy {
			detail = Objects.requireNonNull(detail);
		}
	}

	public record LoadFailed(Throwable failure) implements LoadResult {
		public LoadFailed {
			failure = Objects.requireNonNull(failure);
		}
	}
}
