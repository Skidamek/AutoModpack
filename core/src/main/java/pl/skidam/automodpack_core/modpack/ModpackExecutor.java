package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import pl.skidam.automodpack_core.utils.Throwables;
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
	private final HostingBinder hostingBinder;

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
		this(serverRoot, groupRoot, generationRoot, generationStore, candidateScan, creationExecutor, hosting -> {
			if (hostServer != null) hostServer.replacePaths(hosting);
		});
	}

	ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot, GenerationStore generationStore, CandidateScan candidateScan,
			ThreadPoolExecutor creationExecutor, HostingBinder hostingBinder) {
		this.serverRoot = serverRoot.toAbsolutePath().normalize();
		this.groupRoot = groupRoot.toAbsolutePath().normalize();
		this.generationRoot = generationRoot.toAbsolutePath().normalize();
		this.patchNotesFile = this.groupRoot.resolve(PATCH_NOTES_FILE).normalize();
		this.generationStore = Objects.requireNonNull(generationStore);
		this.dataLayout = new DataRootResolver.Layout(this.generationStore.objectRoot().getParent());
		this.candidateScan = Objects.requireNonNull(candidateScan);
		this.creationExecutor = Objects.requireNonNull(creationExecutor);
		this.hostingBinder = Objects.requireNonNull(hostingBinder);
	}

	@FunctionalInterface
	interface CandidateScan {
		ModpackCandidate scan(ModpackCandidateScanner.Request request) throws CandidateBuildException;
	}

	@FunctionalInterface
	interface HostingBinder {
		void bind(GenerationHosting hosting) throws Exception;
	}

	public PreviewResult preview() {
		return preview(null);
	}

	public PreviewResult preview(String inlineNotes) {
		OperationLease operation = acquire(false);
		if (operation == null) return new PreviewResult.Rejected("Another modpack operation is already in progress", null);
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
			return new PreviewResult.Rejected(Throwables.detail(e), e);
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
			return new PublishResult.Rejected("Guard token must be a canonical 40-character lowercase SHA-1", null);
		return publishInternal(expectedContentToken, inlineNotes);
	}

	public RevertResult revert(long targetSeq, String inlineNotes) {
		if (targetSeq < 1) return new RevertResult.Rejected("Rollback target must be a positive journal sequence", null);
		OperationLease operation = acquire(true);
		if (operation == null) return new RevertResult.Rejected("Another modpack operation is already in progress", null);
		try (operation) {
			return bindHosting(revertLocked(targetSeq, inlineNotes));
		} catch (IllegalArgumentException e) {
			return new RevertResult.Rejected(e.getMessage() == null ? "Invalid rollback target" : e.getMessage(), e);
		} catch (Exception e) {
			LOGGER.error("Failed to publish modpack revert", e);
			return new RevertResult.Rejected(Throwables.detail(e), e);
		}
	}

	private RevertResult revertLocked(long targetSeq, String inlineNotes) throws Exception {
		GenerationStore.Publication publication = null;
		try {
			GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
			publication = generationStore.publishRestore(targetSeq, notes.text());
			consumePatchNotes(notes);
			PackDocument document = new PackDocument(publication.manifest(), publication.entry().contentToken(), publication.entry().policySha1(),
					publication.entry().createdAt(), publication.ledger());
			return new Reverted(document, targetSeq, List.of(), publication.hostingPaths());
		} catch (Exception e) {
			if (publication == null) throw e;
			return new Reverted(currentDocument(publication), targetSeq, List.of("Revert published, but post-publication cleanup was incomplete"), publication.hostingPaths());
		}
	}

	public List<JournalEntry> technicalHistory(int limit) throws IOException {
		return generationStore.history(limit);
	}

	/**
	 * Writes the URL-contract tree (head, journal, objects/&lt;sha1&gt;) as byte-for-byte copies of the hosted files, ready for
	 * any static HTTPS host. Nothing already in the target directory is ever deleted, so stale objects from old generations
	 * may accumulate there; the operator owns the directory. Orthogonal to hosting: works in every connection mode.
	 */
	public int exportHttp(Path targetDirectory) throws IOException {
		Path target = (targetDirectory.isAbsolute() ? targetDirectory : serverRoot.resolve(targetDirectory)).normalize();
		int written = 0;
		for (Map.Entry<String, Path> entry : generationStore.hosting().asMap().entrySet()) {
			String key = entry.getKey();
			Path destination;
			if (key.equals(GenerationHosting.HEAD_DOCUMENT_KEY) || key.equals(GenerationHosting.JOURNAL_KEY)) destination = target.resolve(key);
			else if (HashUtils.isSha1(key)) destination = target.resolve("objects").resolve(HashUtils.normalizeSha1(key));
			else throw new IOException("Unexpected hosting key in the generation store: " + key);
			Files.createDirectories(destination.getParent());
			Files.copy(entry.getValue(), destination, StandardCopyOption.REPLACE_EXISTING);
			written++;
		}
		return written;
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
		if (operation == null) return new PublishResult.Rejected("Another modpack operation is already in progress", null);
		try (operation) {
			return bindHosting(publishLocked(expectedContentToken, inlineNotes));
		} catch (Exception e) {
			LOGGER.error("Failed to publish modpack generation", e);
			return new PublishResult.Rejected(Throwables.detail(e), e);
		}
	}

	private PublishResult publishLocked(String expectedContentToken, String inlineNotes) throws Exception {
		GenerationStore.Publication publication = null;
		CandidateState committedState = null;
		try {
			GenerationStore.Current current = generationStore.loadCurrent().orElse(null);
			if (expectedContentToken != null && current == null)
				return new PublishResult.Rejected("A state guard is unavailable before the root generation is published", null);
			try (ModpackCandidate candidate = buildCandidate(current, true)) {
				GenerationDiff diff = GenerationDiff.between(current == null ? null : current.manifest(), candidate.manifest());
				String token = ContentTree.tokenOf(candidate.manifest());
				CandidateState candidateState = candidateState(current, candidate, token, diff, Optional.empty());
				if (expectedContentToken != null && !expectedContentToken.equals(token))
					return new PublishResult.Rejected("Fresh candidate content does not match the requested guard", null);

				if (current != null && current.contentToken().equals(token))
					return new NoChanges(candidateState.withoutPatchNotesSource(), currentDocument(current), List.of(), generationStore.hosting());

				GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
				candidateState = candidateState.withPatchNotesSource(notes.source());
				publication = generationStore.publish(candidate, notes.text());
				committedState = candidateState;
				consumePatchNotes(notes);
				return new Published(candidateState, currentDocument(publication), List.of(), publication.hostingPaths());
			}
		} catch (Exception e) {
			if (publication == null || committedState == null) throw e;
			return new Published(committedState, currentDocument(publication), List.of("Publication committed, but candidate staging cleanup was incomplete"), publication.hostingPaths());
		}
	}

	public LoadResult loadLast() {
		OperationLease operation = acquire(false);
		if (operation == null) return new LoadResult.Rejected("Another modpack operation is already in progress", null);
		try (operation) {
			GenerationStore.Current current = generationStore.loadCurrent().orElseThrow(() -> new IOException("No modpack journal exists"));
			return bindHosting(new Loaded(currentDocument(current), generationStore.hosting()));
		} catch (Exception e) {
			LOGGER.error("Failed to load the current modpack generation", e);
			return new LoadResult.Rejected(Throwables.detail(e), e);
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
					serverConfig.syncLoaderVersion ? LOADER_VERSION : null, MC_VERSION, serverRoot, groupRoot, serverConfig.modpack,
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

	/**
	 * Hosting follows the committed generation of every outcome that carries one, bound once here inside the operation lease instead of remembered per code path; a failed swap is reported on the committed outcome, never
	 * as a rejection of a durable commit.
	 */
	private <R extends HostingOutcome> R bindHosting(R result) {
		if (!(result instanceof CommittedOutcome committed)) return result;
		R bound = result;
		try {
			hostingBinder.bind(committed.hosting());
		} catch (Exception e) {
			LOGGER.error("The generation committed, but the hosting swap failed", e);
			@SuppressWarnings("unchecked")
			R failed = (R) committed.withHostingFailure(e);
			bound = failed;
		}
		autoExportHttp();
		return bound;
	}

	/** Publish-time mirror of the URL contract for static hosting; a failed export is logged loudly but never fails the committed publication. */
	private void autoExportHttp() {
		String directory = serverConfig == null || serverConfig.exportHttpDirectory == null ? "" : serverConfig.exportHttpDirectory.trim();
		if (directory.isEmpty()) return;
		try {
			int written = exportHttp(Path.of(directory));
			LOGGER.info("Exported the HTTP contract tree ({} files) to {}", written, directory);
		} catch (Exception e) {
			LOGGER.error("Failed to export the HTTP contract tree to {}", directory, e);
		}
	}

	private void consumePatchNotes(GenerationPatchNotes.Resolution notes) {
		if (notes.isFileSourced()) {
			GenerationPatchNotes.CleanupResult cleanup = notes.consumeIfUnchanged();
			if (!cleanup.warning().isEmpty()) LOGGER.warn("Patch notes cleanup: {}", cleanup.warning());
		}
		try {
			GenerationPatchNotes.ensurePresent(patchNotesFile);
		} catch (IOException e) {
			LOGGER.warn("Patch notes file could not be kept present", e);
		}
	}

	private static void validateConfiguration() throws CandidateBuildException {
		if (serverConfig == null || serverConfig.modpack == null || serverConfig.modpack.isEmpty())
			throw new CandidateBuildException("Server group configuration is missing");
		for (var categoryEntry : serverConfig.modpack.entrySet()) {
			var category = categoryEntry.getValue();
			if (category == null) throw new CandidateBuildException("Category '" + categoryEntry.getKey() + "' has no declaration");
			for (var entry : category.entrySet()) {
				try {
					GroupManifestValidator.requireIdentifier(entry.getKey());
				} catch (IllegalArgumentException e) {
					throw new CandidateBuildException(e.getMessage(), e);
				}
				if (entry.getValue() == null) throw new CandidateBuildException("Group '" + entry.getKey() + "' has no declaration");
			}
		}
	}

	private void prepareDirectories() throws IOException, CandidateBuildException {
		Map<String, Path> groupDirectories = new TreeMap<>();
		for (var categoryEntry : serverConfig.modpack.entrySet()) {
			if (categoryEntry.getValue() == null) continue;
			for (String groupId : categoryEntry.getValue().keySet()) {
				Path groupDirectory = groupRoot.resolve(groupId).normalize();
				if (!groupDirectory.startsWith(groupRoot)) throw new CandidateBuildException("Group directory escapes host-modpack: " + groupId);
				if (groupDirectory.startsWith(generationRoot) || generationRoot.startsWith(groupDirectory))
					throw new CandidateBuildException("Group directory overlaps managed generation store: " + groupId);
				groupDirectories.put(groupId, groupDirectory);
			}
		}
		Files.createDirectories(groupRoot);
		GenerationPatchNotes.ensurePresent(patchNotesFile);
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

	public record CandidateSummary(int groups, int files, int objects, List<ExcludedCandidate> excluded, GenerationDiff.Summary diff) {
		public CandidateSummary {
			if (groups < 0 || files < 0 || objects < 0) throw new IllegalArgumentException("Negative generation summary count");
			Objects.requireNonNull(excluded, "excluded");
			excluded = List.copyOf(excluded);
			Objects.requireNonNull(diff, "diff");
		}

		public int exclusions() {
			return excluded.size();
		}

		static CandidateSummary from(ModpackCandidate candidate, GenerationDiff diff) {
			int files = candidate.manifest().groups().values().stream().mapToInt(group -> group.files().size()).sum();
			return new CandidateSummary(candidate.manifest().groups().size(), files, candidate.objects().size(), candidate.exclusions(), diff.summary());
		}

		static CandidateSummary empty() {
			return new CandidateSummary(0, 0, 0, List.of(), new GenerationDiff.Summary(0, 0, 0, 0, 0));
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

	public sealed interface PreviewResult permits PreviewReady, PreviewResult.Rejected {

		/** The preview produced no generation; the detail explains the refusal or failure. */
		record Rejected(String detail, Throwable cause) implements PreviewResult {
			public Rejected {
				detail = Objects.requireNonNull(detail);
			}
		}
	}

	public record PreviewReady(CandidateState state) implements PreviewResult {
		public PreviewReady {
			Objects.requireNonNull(state, "state");
			if (state.patchNotesSource().isEmpty()) throw new IllegalArgumentException("Preview result requires a resolved patch-note source");
		}
	}

	public sealed interface RevertResult extends HostingOutcome permits Reverted, RevertResult.Rejected {

		/** The revert produced no generation; the detail explains the refusal or failure. */
		record Rejected(String detail, Throwable cause) implements RevertResult {
			public Rejected {
				detail = Objects.requireNonNull(detail);
			}
		}
	}

	public record Reverted(PackDocument current, long targetSeq, List<String> warnings, GenerationHosting hosting, Throwable hostingSwapFailure) implements RevertResult, CommittedOutcome {
		public Reverted {
			Objects.requireNonNull(current, "current");
			if (targetSeq < 1) throw new IllegalArgumentException("Invalid rollback target sequence");
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			Objects.requireNonNull(hosting, "hosting");
		}

		public Reverted(PackDocument current, long targetSeq, List<String> warnings, GenerationHosting hosting) {
			this(current, targetSeq, warnings, hosting, null);
		}

		@Override
		public Reverted withHostingFailure(Throwable failure) {
			return new Reverted(current, targetSeq, warnings, hosting, failure);
		}
	}

	public sealed interface PublishResult extends HostingOutcome permits Published, NoChanges, PublishResult.Rejected {

		/** The publication produced no generation; the detail explains the refusal or failure. */
		record Rejected(String detail, Throwable cause) implements PublishResult {
			public Rejected {
				detail = Objects.requireNonNull(detail);
			}
		}
	}

	public record Published(CandidateState state, PackDocument current, List<String> warnings, GenerationHosting hosting, Throwable hostingSwapFailure) implements PublishResult, CommittedOutcome {
		public Published {
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(current, "current");
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			Objects.requireNonNull(hosting, "hosting");
			if (!current.contentToken().equals(state.contentToken()))
				throw new IllegalArgumentException("Published generation content does not match the candidate");
			if (state.patchNotesSource().isEmpty()) throw new IllegalArgumentException("Published generation requires a resolved patch-note source");
		}

		public Published(CandidateState state, PackDocument current, List<String> warnings, GenerationHosting hosting) {
			this(state, current, warnings, hosting, null);
		}

		@Override
		public Published withHostingFailure(Throwable failure) {
			return new Published(state, current, warnings, hosting, failure);
		}
	}

	public record NoChanges(CandidateState state, PackDocument current, List<String> warnings, GenerationHosting hosting, Throwable hostingSwapFailure) implements PublishResult, CommittedOutcome {
		public NoChanges {
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(current, "current");
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			Objects.requireNonNull(hosting, "hosting");
			if (!current.contentToken().equals(state.contentToken()))
				throw new IllegalArgumentException("Current generation content does not match the unchanged candidate");
			if (state.patchNotesSource().isPresent()) throw new IllegalArgumentException("No-change result cannot resolve patch notes");
		}

		public NoChanges(CandidateState state, PackDocument current, List<String> warnings, GenerationHosting hosting) {
			this(state, current, warnings, hosting, null);
		}

		@Override
		public NoChanges withHostingFailure(Throwable failure) {
			return new NoChanges(state, current, warnings, hosting, failure);
		}
	}

	public sealed interface LoadResult extends HostingOutcome permits Loaded, LoadResult.Rejected {

		/** The load produced no generation; the detail explains the refusal or failure. */
		record Rejected(String detail, Throwable cause) implements LoadResult {
			public Rejected {
				detail = Objects.requireNonNull(detail);
			}
		}
	}

	public record Loaded(PackDocument current, GenerationHosting hosting, Throwable hostingSwapFailure) implements LoadResult, CommittedOutcome {
		public Loaded {
			Objects.requireNonNull(current, "current");
			Objects.requireNonNull(hosting, "hosting");
		}

		public Loaded(PackDocument current, GenerationHosting hosting) {
			this(current, hosting, null);
		}

		@Override
		public Loaded withHostingFailure(Throwable failure) {
			return new Loaded(current, hosting, failure);
		}
	}
}
