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
import pl.skidam.automodpack_core.modpack.generation.ContentTree;
import pl.skidam.automodpack_core.modpack.generation.GenerationDiff;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNotes;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;
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
		this(serverRoot, groupRoot, generationRoot, new GenerationStore(generationRoot, DataRootResolver.resolve(serverRoot).layout().objectsDirectory()), new ModpackCandidateScanner()::scan,
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
			GenerationStore.Current current = generationStore.loadCurrent().orElse(null);
			try (ModpackCandidate candidate = buildCandidate(current, false)) {
				GenerationDiff diff = GenerationDiff.between(current == null ? null : current.manifest(), candidate.manifest());
				String token = ContentTree.tokenOf(candidate.manifest());
				GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
				return new PreviewReady(candidateState(current, candidate, token, diff, Optional.of(notes.source())));
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

	public PublishResult publishIfContent(String expectedContentToken) {
		return publishIfContent(expectedContentToken, null);
	}

	public PublishResult publishIfContent(String expectedContentToken, String inlineNotes) {
		if (!HashUtils.isCanonicalSha1(expectedContentToken))
			return new PublishInvalidGuard("Guard token must be a canonical 40-character lowercase SHA-1");
		return publishInternal(expectedContentToken, inlineNotes);
	}

	public RevertResult revert(long targetSeq, String inlineNotes) {
		if (targetSeq < 1) return new RevertInvalidTarget("Rollback target must be a positive journal sequence");
		OperationLease operation = acquire(true);
		if (operation == null) return new RevertBusy("Another modpack operation is already in progress");
		GenerationStore.Publication publication = null;
		try (operation) {
			GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
			publication = generationStore.publishRestore(targetSeq, notes.text());
			postPublication(publication.hostingPaths(), notes);
			PackDocument document = new PackDocument(publication.manifest(), publication.entry().contentToken(), publication.entry().policySha1(),
					publication.entry().createdAt(), publication.ledger());
			return new Reverted(document, targetSeq, List.of());
		} catch (IllegalArgumentException e) {
			return new RevertInvalidTarget(e.getMessage() == null ? "Invalid rollback target" : e.getMessage());
		} catch (Exception e) {
			if (publication != null) return new Reverted(currentDocument(publication), targetSeq, List.of("Revert published, but post-publication cleanup was incomplete"));
			LOGGER.error("Failed to publish modpack revert", e);
			return new RevertFailed(e);
		}
	}

	public List<JournalEntry> technicalHistory(int limit) throws IOException {
		return generationStore.history(limit);
	}

	public GenerationStore.StorageReport storageReport() throws IOException {
		return generationStore.measureStorage();
	}

	public GenerationStore.CollectionSummary collectUnreachableObjects() throws IOException {
		OperationLease operation = acquire(true);
		if (operation == null) throw new IOException("Another modpack operation is already in progress");
		try (operation) {
			return generationStore.collectUnreachable();
		}
	}

	private PublishResult publishInternal(String expectedContentToken, String inlineNotes) {
		OperationLease operation = acquire(true);
		if (operation == null) return new PublishBusy("Another modpack operation is already in progress");
		GenerationStore.Publication publication = null;
		PublishResult committedResult = null;
		CandidateState committedState = null;
		try (operation) {
			GenerationStore.Current current = generationStore.loadCurrent().orElse(null);
			if (expectedContentToken != null && current == null)
				return new PublishGuardUnsupported("A state guard is unavailable before the root generation is published");
			try (ModpackCandidate candidate = buildCandidate(current, true)) {
				GenerationDiff diff = GenerationDiff.between(current == null ? null : current.manifest(), candidate.manifest());
				String token = ContentTree.tokenOf(candidate.manifest());
				CandidateState candidateState = candidateState(current, candidate, token, diff, Optional.empty());
				if (expectedContentToken != null && !expectedContentToken.equals(token))
					return new PublishGuardMismatch(candidateState, "Fresh candidate content does not match the requested guard");

				GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
				if (current != null && current.contentToken().equals(token)) {
					publication = null;
					candidateState = candidateState.withoutPatchNotesSource();
					committedResult = new NoChanges(candidateState, currentDocument(current), List.of());
					return committedResult;
				}

				candidateState = candidateState.withPatchNotesSource(notes.source());
				publication = generationStore.publish(candidate, notes.text());
				committedState = candidateState;
				postPublication(publication.hostingPaths(), notes);
				committedResult = new Published(candidateState, currentDocument(publication), List.of());
				return committedResult;
			}
		} catch (Exception e) {
			if (publication != null && committedState != null) {
				List<String> warnings = new ArrayList<>();
				if (committedResult instanceof Published published) warnings.addAll(published.warnings());
				warnings.add("Publication committed, but candidate staging cleanup was incomplete");
				return new Published(committedState, currentDocument(publication), warnings);
			}
			LOGGER.error("Failed to publish modpack generation", e);
			return new PublishFailed(e);
		}
	}

	public LoadResult loadLast() {
		OperationLease operation = acquire(false);
		if (operation == null) return new LoadBusy("Another modpack operation is already in progress");
		try (operation) {
			GenerationStore.Current current = generationStore.loadCurrent().orElseThrow(() -> new IOException("No modpack journal exists"));
			try {
				replaceHosting(generationStore.hosting());
				return new Loaded(currentDocument(current));
			} catch (Exception e) {
				LOGGER.error("Failed to activate the current modpack generation", e);
				return new LoadFailed(e);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load the current modpack generation", e);
			return new LoadFailed(e);
		}
	}

	public Optional<PackDocument> currentDocument() throws IOException {
		return generationStore.loadCurrent().map(this::currentDocument);
	}

	private PackDocument currentDocument(GenerationStore.Current current) {
		return new PackDocument(current.manifest(), current.contentToken(), current.policySha1(), current.createdAt(), current.ledger());
	}

	private PackDocument currentDocument(GenerationStore.Publication publication) {
		return new PackDocument(publication.manifest(), publication.entry().contentToken(), publication.entry().policySha1(), publication.entry().createdAt(), publication.ledger());
	}

	private CandidateState candidateState(GenerationStore.Current current, ModpackCandidate candidate, String token, GenerationDiff diff, Optional<GenerationPatchNotes.Source> source) {
		return new CandidateState(Optional.ofNullable(current).map(this::currentDocument), token, diff, CandidateSummary.from(candidate, diff), source);
	}

	private ModpackCandidate buildCandidate(GenerationStore.Current previous, boolean materializeMissingObjects) throws IOException, CandidateBuildException {
		validateConfiguration();
		prepareDirectories();
		String modpackId = previous == null ? ModpackId.generate() : ModpackId.requireValid(previous.manifest().modpackId());
		try (FileCache fileCache = FileCache.open(dataLayout.fileCacheDirectory());
				ModFileCache modFileCache = ModFileCache.open(dataLayout.modCacheDirectory())) {
			ModpackCandidateScanner.Request request = new ModpackCandidateScanner.Request(modpackId, serverConfig.modpackName, AM_VERSION, LOADER,
					serverConfig.syncLoaderVersion ? LOADER_VERSION : null, MC_VERSION, serverRoot, groupRoot, serverConfig.groups,
					serverConfig.autoExcludeUnnecessaryFiles, serverConfig.autoExcludeServerSideMods, generationRoot.resolve(SERVER_STAGING_DIR.getFileName()), creationExecutor,
					generationStore.objectRoot(), fileCache, modFileCache, materializeMissingObjects);
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

	private void postPublication(GenerationHosting hosting, GenerationPatchNotes.Resolution notes) {
		try {
			replaceHosting(hosting);
		} catch (Exception e) {
			LOGGER.warn("Published generation is current but hosting replacement failed", e);
		}
		if (notes != null && notes.isFileSourced()) {
			GenerationPatchNotes.CleanupResult cleanup = notes.consumeIfUnchanged();
			if (!cleanup.warning().isEmpty()) LOGGER.warn("Patch notes cleanup: {}", cleanup.warning());
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

	public record CandidateState(Optional<PackDocument> parent, String contentToken, GenerationDiff diff, CandidateSummary summary,
			Optional<GenerationPatchNotes.Source> patchNotesSource) {
		public CandidateState {
			Objects.requireNonNull(parent, "parent");
			if (!HashUtils.isCanonicalSha1(contentToken)) throw new IllegalArgumentException("Invalid candidate content token");
			Objects.requireNonNull(diff, "diff");
			Objects.requireNonNull(summary, "summary");
			Objects.requireNonNull(patchNotesSource, "patch notes source");
		}

		CandidateState withPatchNotesSource(GenerationPatchNotes.Source source) {
			return new CandidateState(parent, contentToken, diff, summary, Optional.of(source));
		}

		CandidateState withoutPatchNotesSource() {
			return new CandidateState(parent, contentToken, diff, summary, Optional.empty());
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

	public record Reverted(PackDocument current, long targetSeq, List<String> warnings) implements RevertResult {
		public Reverted {
			Objects.requireNonNull(current, "current");
			if (targetSeq < 1) throw new IllegalArgumentException("Invalid rollback target sequence");
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
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

	public record Published(CandidateState state, PackDocument current, List<String> warnings) implements PublishResult {
		public Published {
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(current, "current");
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			if (!current.contentToken().equals(state.contentToken()))
				throw new IllegalArgumentException("Published generation content does not match the candidate");
			if (state.patchNotesSource().isEmpty()) throw new IllegalArgumentException("Published generation requires a resolved patch-note source");
		}
	}

	public record NoChanges(CandidateState state, PackDocument current, List<String> warnings) implements PublishResult {
		public NoChanges {
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(current, "current");
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			if (!current.contentToken().equals(state.contentToken()))
				throw new IllegalArgumentException("Current generation content does not match the unchanged candidate");
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

	public record Loaded(PackDocument current) implements LoadResult {
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
