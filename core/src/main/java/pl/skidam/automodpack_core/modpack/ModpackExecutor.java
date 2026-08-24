package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import pl.skidam.automodpack_core.modpack.candidate.CandidateBuildException;
import pl.skidam.automodpack_core.modpack.candidate.ExcludedCandidate;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidateScanner;
import pl.skidam.automodpack_core.modpack.generation.GenerationDiff;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryEntry;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.generation.GenerationIdentity;
import pl.skidam.automodpack_core.modpack.generation.GenerationMetadata;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNotes;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.HashUtils;
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
	private final DataRootResolver.Layout dataLayout;
	private final CandidateScan candidateScan;

	public ModpackExecutor() {
		this(GameDirectory.current(), HOST_MODPACK_DIR, GameDirectory.current().resolve(SERVER_DIR));
	}

	public ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot) {
		this(serverRoot, groupRoot, generationRoot, new GenerationStore(generationRoot, DataRootResolver.resolve(serverRoot)), new ModpackCandidateScanner()::scan,
				(ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() * 2),
						new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build()));
	}

	ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot, GenerationStore generationStore, CandidateScan candidateScan,
			ThreadPoolExecutor creationExecutor) {
		this.serverRoot = serverRoot.toAbsolutePath().normalize();
		this.groupRoot = groupRoot.toAbsolutePath().normalize();
		this.generationRoot = generationRoot.toAbsolutePath().normalize();
		this.patchNotesFile = this.serverRoot.resolve(PATCH_NOTES_FILE).normalize();
		this.generationStore = Objects.requireNonNull(generationStore);
		this.dataLayout = new DataRootResolver.Layout(this.generationStore.objectRoot().getParent());
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
		OperationLease operation = acquire(false);
		if (operation == null) return new PreviewBusy("Another modpack operation is already in progress");
		try (operation) {
			Optional<GenerationStore.CurrentSnapshot> previous = generationStore.loadCurrent();
			GenerationStore.CurrentSnapshot previousSnapshot = previous.orElse(null);
			try (ModpackCandidate candidate = buildCandidate(previousSnapshot)) {
				GenerationDiff diff = GenerationDiff.between(previous.map(snapshot -> snapshot.record().manifest()).orElse(null), candidate.manifest());
				String stateDigest = GenerationIdentity.stateDigest(candidate.manifest());
				GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
				return new PreviewReady(candidateState(previousSnapshot, candidate, stateDigest, diff, notes.source()));
			}
		} catch (Exception e) {
			LOGGER.error("Failed to preview modpack generation", e);
			return new PreviewFailed(e);
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
		if (!HashUtils.isCanonicalSha1(expectedStateDigest))
			return new PublishInvalidGuard("Guard digest must be a canonical 40-character lowercase SHA-1");
		return publishInternal(expectedStateDigest, inlineNotes);
	}

	public RevertResult revert(String targetGenerationId, String inlineNotes) {
		if (!HashUtils.isCanonicalSha1(targetGenerationId))
			return new RevertInvalidTarget("Rollback target must be a canonical 40-character lowercase SHA-1");
		OperationLease operation = acquire(true);
		if (operation == null) return new RevertBusy("Another modpack operation is already in progress");
		GenerationStore.Publication publication = null;
		try (operation) {
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
		}
	}

	public List<GenerationHistoryEntry> technicalHistory() throws IOException {
		return generationStore.currentHistory();
	}

	public Optional<GenerationHistoryIndex> historyIndex() throws IOException {
		return generationStore.currentHistoryIndex();
	}

	public GenerationStore.StorageReport storageReport() throws IOException {
		return generationStore.measureStorage();
	}

	public GenerationStore.CompactionPreview previewCompactHistory(String boundaryGenerationId) throws IOException {
		OperationLease operation = acquire(false);
		if (operation == null) throw new IOException("Another modpack operation is already in progress");
		try (operation) {
			return generationStore.previewCompaction(boundaryGenerationId);
		}
	}

	public GenerationStore.CompactionResult compactHistoryBefore(String boundaryGenerationId) throws IOException {
		OperationLease operation = acquire(true);
		if (operation == null) throw new IOException("Another modpack operation is already in progress");
		try (operation) {
			return generationStore.compactBefore(boundaryGenerationId);
		}
	}

	private PublishResult publishInternal(String expectedStateDigest, String inlineNotes) {
		OperationLease operation = acquire(true);
		if (operation == null) return new PublishBusy("Another modpack operation is already in progress");
		GenerationStore.Publication publication = null;
		PublishResult committedResult = null;
		CandidateState committedState = null;
		try (operation) {
			Optional<GenerationStore.CurrentSnapshot> previous = generationStore.loadCurrent();
			if (expectedStateDigest != null && previous.isEmpty())
				return new PublishGuardUnsupported("A state guard is unavailable before the root generation is published");
			GenerationStore.CurrentSnapshot previousSnapshot = previous.orElse(null);
			try (ModpackCandidate candidate = buildCandidate(previousSnapshot)) {
				GenerationRecord parent = previous.map(GenerationStore.CurrentSnapshot::record).orElse(null);
				GenerationDiff diff = GenerationDiff.between(parent == null ? null : parent.manifest(), candidate.manifest());
				String stateDigest = GenerationIdentity.stateDigest(candidate.manifest());
				CandidateSummary summary = CandidateSummary.from(candidate, diff);
				CandidateState candidateState = new CandidateState(previous.map(GenerationStore.CurrentSnapshot::record), stateDigest, diff, summary, Optional.empty());
				if (expectedStateDigest != null && !expectedStateDigest.equals(stateDigest))
					return new PublishGuardMismatch(candidateState, "Fresh candidate state does not match the requested guard");

				GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
				if (parent != null && parent.metadata().stateDigest().equals(stateDigest)) {
					if (notes.source() == GenerationPatchNotes.Source.EMPTY) {
						publication = generationStore.publish(candidate, previous, parent.metadata().patchNotes());
						committedState = candidateState;
						committedResult = finishPublication(publication, committedState, null);
						return committedResult;
					}
					candidateState = candidateState.withPatchNotesSource(notes.source());
					publication = generationStore.publish(candidate, previous, notes.text());
					committedState = candidateState;
					committedResult = finishPublication(publication, committedState, notes);
					return committedResult;
				}

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
		}
	}

	private PublishResult finishPublication(GenerationStore.Publication publication, CandidateState state, GenerationPatchNotes.Resolution notes) {
		List<String> warnings = new ArrayList<>();
		postPublication(publication, notes, warnings);
		if (publication.status() == GenerationStore.PublicationStatus.PUBLISHED)
			return new Published(state, publication.record(), warnings);
		return new NoChanges(state.withoutPatchNotesSource(), publication.record(), warnings);
	}

	private void postPublication(GenerationStore.Publication publication, GenerationPatchNotes.Resolution notes, List<String> warnings) {
		try {
			replaceHosting(publication.hostingPaths());
		} catch (Exception e) {
			warnings.add("Published generation could not fully replace the active hosting map");
			LOGGER.warn("Published generation is current but hosting replacement failed", e);
		}
		if (publication.status() == GenerationStore.PublicationStatus.PUBLISHED && notes != null && notes.isFileSourced()) {
			GenerationPatchNotes.CleanupResult cleanup = notes.consumeIfUnchanged();
			if (!cleanup.warning().isEmpty()) warnings.add(cleanup.warning());
		}
	}

	public LoadResult loadLast() {
		OperationLease operation = acquire(false);
		if (operation == null) return new LoadBusy("Another modpack operation is already in progress");
		try (operation) {
			GenerationStore.CurrentSnapshot current = generationStore.loadCurrentAndRepair().orElseThrow(() -> new IOException("No current generation pointer exists"));
			try {
				replaceHosting(current.hostingPaths());
				return new Loaded(current.record());
			} catch (Exception e) {
				LOGGER.error("Failed to activate the current modpack generation", e);
				return new LoadFailed(e);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load the current modpack generation", e);
			return new LoadFailed(e);
		}
	}

	public Optional<GenerationRecord> currentRecord() throws IOException {
		return generationStore.loadCurrent().map(GenerationStore.CurrentSnapshot::record);
	}

	private CandidateState candidateState(GenerationStore.CurrentSnapshot previous, ModpackCandidate candidate, String stateDigest,
			GenerationDiff diff, GenerationPatchNotes.Source source) {
		return new CandidateState(Optional.ofNullable(previous).map(GenerationStore.CurrentSnapshot::record), stateDigest, diff, CandidateSummary.from(candidate, diff), Optional.of(source));
	}

	private ModpackCandidate buildCandidate(GenerationStore.CurrentSnapshot previous) throws IOException, CandidateBuildException {
		validateConfiguration();
		prepareDirectories();
		String modpackId = previous == null ? ModpackId.generate() : ModpackId.requireValid(previous.record().manifest().modpackId());
		try (FileMetadataCache fileMetadataCache = FileMetadataCache.open(dataLayout.fileMetadataDirectory());
				ModFileCache modFileCache = ModFileCache.open(dataLayout.modMetadataDirectory())) {
			ModpackCandidateScanner.Request request = new ModpackCandidateScanner.Request(modpackId, serverConfig.modpackName, AM_VERSION, LOADER,
					LOADER_VERSION, MC_VERSION, serverRoot, groupRoot, serverConfig.groups,
					serverConfig.autoExcludeUnnecessaryFiles, serverConfig.autoExcludeServerSideMods, generationRoot.resolve(SERVER_STAGING_DIR.getFileName()), creationExecutor,
					generationStore.objectRoot(), fileMetadataCache, modFileCache);
			ModpackCandidate candidate = candidateScan.scan(request);
			for (ExcludedCandidate exclusion : candidate.exclusions())
				LOGGER.info("Excluded from the modpack: {}/{} - {} ({})", exclusion.source().groupId(), exclusion.source().logicalPath(),
						exclusion.reason().name().toLowerCase(Locale.ROOT), exclusion.message());
			return candidate;
		}
	}

	private OperationLease acquire(boolean publication) {
		if (!scanActive.compareAndSet(false, true)) return null;
		if (publication && !publicationActive.compareAndSet(false, true)) {
			scanActive.set(false);
			return null;
		}
		return new OperationLease(publication);
	}

	private final class OperationLease implements AutoCloseable {
		private final boolean publication;
		private boolean closed;

		private OperationLease(boolean publication) {
			this.publication = publication;
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			if (publication) publicationActive.set(false);
			scanActive.set(false);
		}
	}

	private void replaceHosting(GenerationHosting paths) {
		if (hostServer != null) {
			hostServer.replacePaths(paths);
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
		Files.createDirectories(main.resolve(ModpackPathPolicy.MODS_ROOT));
		Files.createDirectories(main.resolve(ModpackPathPolicy.CONFIG_ROOT));
		Files.createDirectories(main.resolve(ModpackPathPolicy.SHADERPACKS_ROOT));
		Files.createDirectories(main.resolve(ModpackPathPolicy.RESOURCEPACKS_ROOT));
	}

	public boolean isGenerating() {
		return publicationActive.get();
	}

	public void stop() {
		creationExecutor.shutdown();
	}

	public record CandidateSummary(int groups, int files, int objects, List<ExcludedCandidate> excluded, int shadows, GenerationDiff.Summary diff) {
		public CandidateSummary {
			if (groups < 0 || files < 0 || objects < 0 || shadows < 0) throw new IllegalArgumentException("Negative generation summary count");
			Objects.requireNonNull(excluded, "excluded");
			excluded = List.copyOf(excluded);
			Objects.requireNonNull(diff, "diff");
		}

		public int exclusions() {
			return excluded.size();
		}

		static CandidateSummary from(ModpackCandidate candidate, GenerationDiff diff) {
			int files = candidate.manifest().groups().values().stream().mapToInt(group -> group.files().size()).sum();
			return new CandidateSummary(candidate.manifest().groups().size(), files, candidate.objects().size(), candidate.exclusions(), candidate.shadows().size(), diff.summary());
		}

		static CandidateSummary empty() {
			return new CandidateSummary(0, 0, 0, List.of(), 0, new GenerationDiff.Summary(0, 0, 0, 0, 0));
		}
	}

	public record CandidateState(Optional<GenerationRecord> parent, String candidateStateDigest, GenerationDiff diff, CandidateSummary summary,
			Optional<GenerationPatchNotes.Source> patchNotesSource) {
		public CandidateState {
			Objects.requireNonNull(parent, "parent");
			if (!HashUtils.isCanonicalSha1(candidateStateDigest)) throw new IllegalArgumentException("Invalid candidate state digest");
			Objects.requireNonNull(diff, "diff");
			Objects.requireNonNull(summary, "summary");
			Objects.requireNonNull(patchNotesSource, "patch notes source");
		}

		CandidateState withPatchNotesSource(GenerationPatchNotes.Source source) {
			return new CandidateState(parent, candidateStateDigest, diff, summary, Optional.of(source));
		}

		CandidateState withoutPatchNotesSource() {
			return new CandidateState(parent, candidateStateDigest, diff, summary, Optional.empty());
		}
	}

	public sealed interface PreviewResult permits PreviewReady, PreviewBusy, PreviewFailed {}

	public record PreviewReady(CandidateState state) implements PreviewResult {
		public PreviewReady {
			Objects.requireNonNull(state, "state");
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
			Objects.requireNonNull(failure, "failure");
		}
	}

	public sealed interface RevertResult permits Reverted, RevertBusy, RevertInvalidTarget, RevertFailed {}

	public record Reverted(GenerationRecord current, String targetGenerationId, List<String> warnings) implements RevertResult {
		public Reverted {
			Objects.requireNonNull(current, "current");
			if (!HashUtils.isCanonicalSha1(targetGenerationId)) throw new IllegalArgumentException("Invalid rollback target generation ID");
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
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(current, "current");
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
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(current, "current");
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
			Objects.requireNonNull(state, "state");
			detail = Objects.requireNonNull(detail);
			if (state.patchNotesSource().isPresent()) throw new IllegalArgumentException("Guard mismatch cannot resolve patch notes");
		}
	}

	public record PublishFailed(Throwable failure) implements PublishResult {
		public PublishFailed {
			Objects.requireNonNull(failure, "failure");
		}
	}

	public sealed interface LoadResult permits Loaded, LoadBusy, LoadFailed {}

	public record Loaded(GenerationRecord current) implements LoadResult {
		public Loaded {
			Objects.requireNonNull(current, "current");
		}
	}

	public record LoadBusy(String detail) implements LoadResult {
		public LoadBusy {
			detail = Objects.requireNonNull(detail);
		}
	}

	public record LoadFailed(Throwable failure) implements LoadResult {
		public LoadFailed {
			Objects.requireNonNull(failure, "failure");
		}
	}
}
