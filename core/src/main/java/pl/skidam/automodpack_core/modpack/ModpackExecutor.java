package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.HOST_MODPACK_DIR;
import static pl.skidam.automodpack_core.storage.StoragePaths.PATCH_NOTES_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_DIR;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_STAGING_DIR;
import static pl.skidam.automodpack_core.storage.StoragePaths.WAITING_MUSIC_FILE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.loader.ModFileCache;
import pl.skidam.automodpack_core.modpack.PackAbsence.Kind;
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
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.platforms.PlatformSourceLookup;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.Throwables;
import pl.skidam.automodpack_core.utils.cache.FileCache;

public class ModpackExecutor {
	private final ThreadPoolExecutor creationExecutor;
	private final OperationLocks locks = new OperationLocks();
	private final Path serverRoot;
	private final Path groupRoot;
	private final Path generationRoot;
	private final Path patchNotesFile;
	private final GenerationStore generationStore;
	private final DataRootResolver.Layout dataLayout;
	private final CandidateScan candidateScan;
	private final HostingBinder hostingBinder;
	private final Supplier<ServerConfigJsons.ServerConfigFieldsV3> config;
	private final HttpExporter httpExporter;
	private volatile PackAbsence packAbsence;

	public ModpackExecutor() {
		this(GameDirectory.current(), HOST_MODPACK_DIR, GameDirectory.current().resolve(SERVER_DIR), PlatformSourceLookup.resolving());
	}

	public ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot) {
		this(serverRoot, groupRoot, generationRoot, PlatformSourceLookup.none());
	}

	ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot, PlatformSourceLookup platformSourceLookup) {
		this(serverRoot, groupRoot, generationRoot, new Deps(new GenerationStore(generationRoot, DataRootResolver.resolve(serverRoot).layout().objectsDirectory(),
				serverRoot.resolve(HOST_MODPACK_DIR).resolve(WAITING_MUSIC_FILE)), new ModpackCandidateScanner()::scan,
				(ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() * 2),
						new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build()),
				platformSourceLookup));
	}

	ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot, Deps deps) {
		this.serverRoot = serverRoot.toAbsolutePath().normalize();
		this.groupRoot = groupRoot.toAbsolutePath().normalize();
		this.generationRoot = generationRoot.toAbsolutePath().normalize();
		this.patchNotesFile = this.groupRoot.resolve(PATCH_NOTES_FILE).normalize();
		this.generationStore = Objects.requireNonNull(deps.generationStore());
		this.dataLayout = new DataRootResolver.Layout(this.generationStore.objectRoot().getParent());
		this.candidateScan = Objects.requireNonNull(deps.candidateScan());
		this.creationExecutor = Objects.requireNonNull(deps.creationExecutor());
		this.hostingBinder = Objects.requireNonNull(deps.hostingBinder());
		this.config = Objects.requireNonNull(deps.config());
		this.httpExporter = new HttpExporter(this.generationStore, this.serverRoot, this.config, Objects.requireNonNull(deps.platformSourceLookup()), this.locks);
	}

	/** One executor's collaborators; production fills them from the roots, tests swap any of them. */
	record Deps(GenerationStore generationStore, CandidateScan candidateScan, ThreadPoolExecutor creationExecutor, HostingBinder hostingBinder,
			Supplier<ServerConfigJsons.ServerConfigFieldsV3> config, PlatformSourceLookup platformSourceLookup) {
		Deps(GenerationStore generationStore, CandidateScan candidateScan, ThreadPoolExecutor creationExecutor, PlatformSourceLookup platformSourceLookup) {
			this(generationStore, candidateScan, creationExecutor, hosting -> {
				if (hostServer != null) hostServer.replacePaths(hosting);
			}, () -> serverConfig, platformSourceLookup);
		}

		Deps(GenerationStore generationStore, CandidateScan candidateScan, ThreadPoolExecutor creationExecutor) {
			this(generationStore, candidateScan, creationExecutor, hosting -> {
				if (hostServer != null) hostServer.replacePaths(hosting);
			}, () -> serverConfig, PlatformSourceLookup.none());
		}
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
		try (OperationLocks.Lease operation = locks.acquire(false)) {
			if (operation == null) return new Rejected("Another modpack operation is already in progress", null);
			GenerationStore.Current current = generationStore.loadCurrent().orElse(null);
			try (ModpackCandidate candidate = buildCandidate(current, false)) {
				warnUnusableSelectedGroups(candidate.manifest());
				PackAbsence absence = refuse(candidate);
				GenerationDiff diff = GenerationDiff.between(current == null ? null : current.manifest(), candidate.manifest());
				String token = ContentTree.tokenOf(candidate.manifest());
				GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
				return new PreviewReady(candidateState(current, candidate, token, diff, Optional.of(notes.source())), Optional.ofNullable(absence));
			}
		} catch (Exception e) {
			LOGGER.error("Failed to preview modpack generation", e);
			return new Rejected(Throwables.detail(e), e);
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
			return new Rejected("Guard token must be a canonical 40-character lowercase SHA-1", null);
		return publishInternal(expectedContentToken, inlineNotes);
	}

	public RevertResult revert(long targetSeq, String inlineNotes) {
		if (targetSeq < 1) return new Rejected("Rollback target must be a positive journal sequence", null);
		try (OperationLocks.Lease operation = locks.acquire(true)) {
			if (operation == null) return new Rejected("Another modpack operation is already in progress", null);
			return bindHosting(revertLocked(targetSeq, inlineNotes));
		} catch (IllegalArgumentException e) {
			return new Rejected(e.getMessage() == null ? "Invalid rollback target" : e.getMessage(), e);
		} catch (Exception e) {
			LOGGER.error("Failed to publish modpack revert", e);
			return new Rejected(Throwables.detail(e), e);
		}
	}

	private RevertResult revertLocked(long targetSeq, String inlineNotes) throws Exception {
		GenerationStore.Publication publication = null;
		try {
			GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
			publication = generationStore.publishRestore(targetSeq, notes.text());
			consumePatchNotes(notes);
			PackDocument document = new PackDocument(publication.manifest(), publication.entry().contentToken(), publication.entry().policySha1(),
					publication.entry().createdAt(), publication.ledger(), "");
			return new Reverted(document, targetSeq, List.of(), publication.hostingPaths());
		} catch (Exception e) {
			if (publication == null) throw e;
			LOGGER.error("Modpack revert committed, but post-publication cleanup was incomplete", e);
			return new Reverted(currentDocument(publication), targetSeq, List.of("Revert published, but post-publication cleanup was incomplete"), publication.hostingPaths());
		}
	}

	public List<JournalEntry> technicalHistory(int limit) throws IOException {
		return generationStore.history(limit);
	}

	public HttpExporter.Result exportHttp(Path targetDirectory) throws IOException {
		return exportHttp(targetDirectory, false);
	}

	public HttpExporter.Result exportHttp(Path targetDirectory, boolean includeAll) throws IOException {
		return httpExporter.export(targetDirectory, includeAll);
	}

	public GenerationStore.StorageReport storageReport() throws IOException {
		return generationStore.measureStorage();
	}

	public GenerationStore.CollectionSummary collectUnreachableObjects() throws IOException {
		try (OperationLocks.Lease operation = locks.acquire(true)) {
			if (operation == null) throw new IOException("Another modpack operation is already in progress");
			return generationStore.collectUnreachable();
		}
	}

	private PublishResult publishInternal(String expectedContentToken, String inlineNotes) {
		try (OperationLocks.Lease operation = locks.acquire(true)) {
			if (operation == null) return new Rejected("Another modpack operation is already in progress", null);
			PublishResult result = bindHosting(publishLocked(expectedContentToken, inlineNotes));
			if (result instanceof PublishResult.NothingToPublish nothing) packAbsence = nothing.absence();
			return result;
		} catch (Exception e) {
			LOGGER.error("Failed to publish modpack generation", e);
			return new Rejected(Throwables.detail(e), e);
		}
	}

	private PublishResult publishLocked(String expectedContentToken, String inlineNotes) throws Exception {
		GenerationStore.Publication publication = null;
		CandidateState committedState = null;
		try {
			GenerationStore.Current current = generationStore.loadCurrent().orElse(null);
			if (expectedContentToken != null && current == null)
				return new Rejected("A state guard is unavailable before the root generation is published", null);
			try (FileCache fileCache = FileCache.open(dataLayout.fileCacheDirectory());
					ModpackCandidate candidate = buildCandidate(current, true, fileCache)) {
				warnUnusableSelectedGroups(candidate.manifest());
				PackAbsence absence = refuse(candidate);
				if (absence != null) return new PublishResult.NothingToPublish(absence);
				GenerationDiff diff = GenerationDiff.between(current == null ? null : current.manifest(), candidate.manifest());
				String token = ContentTree.tokenOf(candidate.manifest());
				CandidateState candidateState = candidateState(current, candidate, token, diff, Optional.empty());
				if (expectedContentToken != null && !expectedContentToken.equals(token))
					return new Rejected("Fresh candidate content does not match the requested guard", null);

				GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve(inlineNotes, patchNotesFile);
				publication = generationStore.publish(candidate, notes.text(), fileCache);
				if (current != null && publication.entry().seq() == current.seq())
					return new NoChanges(candidateState.withoutPatchNotesSource(), currentDocument(publication), List.of(), publication.hostingPaths());

				candidateState = candidateState.withPatchNotesSource(notes.source());
				committedState = candidateState;
				consumePatchNotes(notes);
				return new Published(candidateState, currentDocument(publication), List.of(), publication.hostingPaths());
			}
		} catch (Exception e) {
			if (publication == null || committedState == null) throw e;
			LOGGER.error("Modpack publication committed, but candidate staging cleanup was incomplete", e);
			return new Published(committedState, currentDocument(publication), List.of("Publication committed, but candidate staging cleanup was incomplete"), publication.hostingPaths());
		}
	}

	public LoadResult loadLast() {
		try (OperationLocks.Lease operation = locks.acquire(false)) {
			if (operation == null) return new Rejected("Another modpack operation is already in progress", null);
			GenerationStore.Current current = generationStore.loadCurrent().orElse(null);
			if (current == null) {
				PackAbsence absence = new PackAbsence(Kind.NEVER_PUBLISHED, "No modpack has been published yet. Run /automodpack generate, or leave generate-modpack-on-start enabled to publish one at startup.");
				packAbsence = absence;
				return new LoadResult.NothingPublished(absence);
			}
			PackDocument document = currentDocument(current);
			warnUnusableSelectedGroups(document.manifest());
			LoadResult loaded = bindHosting(new Loaded(document, generationStore.hosting()));
			// The bind clears the absence; the loaded generation's own emptiness sets it right back when it serves nobody.
			packAbsence = absenceOf(document.manifest());
			return loaded;
		} catch (Exception e) {
			LOGGER.error("Failed to load the current modpack generation", e);
			return new Rejected(Throwables.detail(e), e);
		}
	}

	public Optional<PackDocument> currentDocument() throws IOException {
		return generationStore.loadCurrent().map(this::currentDocument);
	}

	/**
	 * Why this server hosts no modpack, or null when it hosts one. The verdict of the last generation attempt, kept
	 * here so the boot, the login handshake and the commands all read the same answer instead of re-deriving it: a
	 * joining client is told what this server can offer, and a live {@code /automodpack generate} that succeeds clears
	 * the absence without a restart. Clearing lives where hosting binds, so a revert to a real generation
	 * re-advertises it the same way a publish does; a rejected attempt changed nothing about what the store holds, so
	 * the previous verdict stands.
	 */
	public PackAbsence packAbsence() {
		return packAbsence;
	}

	private PackDocument currentDocument(GenerationStore.Current current) {
		return new PackDocument(current.manifest(), current.contentToken(), current.policySha1(), current.createdAt(), current.ledger(), "");
	}

	private PackDocument currentDocument(GenerationStore.Publication publication) {
		return new PackDocument(publication.manifest(), publication.entry().contentToken(), publication.entry().policySha1(), publication.entry().createdAt(), publication.ledger(), "");
	}

	private CandidateState candidateState(GenerationStore.Current current, ModpackCandidate candidate, String token, GenerationDiff diff, Optional<GenerationPatchNotes.Source> source) {
		return new CandidateState(Optional.ofNullable(current).map(this::currentDocument), token, diff, CandidateSummary.from(candidate, diff), source);
	}

	private ModpackCandidate buildCandidate(GenerationStore.Current previous, boolean materializeMissingObjects) throws IOException, CandidateBuildException {
		try (FileCache fileCache = FileCache.open(dataLayout.fileCacheDirectory())) {
			return buildCandidate(previous, materializeMissingObjects, fileCache);
		}
	}

	private ModpackCandidate buildCandidate(GenerationStore.Current previous, boolean materializeMissingObjects, FileCache fileCache) throws IOException, CandidateBuildException {
		validateConfiguration();
		prepareDirectories();
		ServerConfigJsons.ServerConfigFieldsV3 serverConfig = config.get();
		String modpackId = previous == null ? ModpackId.generate() : ModpackId.requireValid(previous.manifest().modpackId());
		try (ModFileCache modFileCache = ModFileCache.open(dataLayout.modCacheDirectory())) {
			// Unadvertised versions leave the manifest fields empty, so clients see a files-only pack instead of a
			// pack whose versions they must switch to.
			boolean advertiseVersions = serverConfig.advertiseVersionsToSync;
			ModpackCandidateScanner.Request request = new ModpackCandidateScanner.Request(modpackId, serverConfig.modpack.name, AM_VERSION,
					advertiseVersions ? LOADER : null, advertiseVersions ? LOADER_VERSION : null, advertiseVersions ? MC_VERSION : null, serverRoot, groupRoot,
					serverConfig.modpack.categories, serverConfig.autoExcludeServerSideMods, generationRoot.resolve(SERVER_STAGING_DIR.getFileName()),
					creationExecutor, generationStore.objectRoot(), fileCache, modFileCache, materializeMissingObjects);
			ModpackCandidate candidate = candidateScan.scan(request);
			for (ExcludedCandidate exclusion : candidate.exclusions())
				LOGGER.info("Excluded from the modpack: {}/{} - {} ({})", exclusion.source().groupId(), exclusion.source().logicalPath(),
						exclusion.reason().name().toLowerCase(Locale.ROOT), exclusion.message());
			return candidate;
		}
	}

	/**
	 * Why this candidate must never be published, or null when it may. Scanning whatever is on disk is the scanner's
	 * whole job and a zero-file manifest is a truthful answer to it; refusing to publish one is a separate policy, and
	 * it lives here so a caller that only wants to look at the candidate is never refused one.
	 */
	private static PackAbsence refuse(ModpackCandidate candidate) {
		GroupManifest manifest = candidate.manifest();
		if (manifest.groups().values().stream().allMatch(group -> group.files().isEmpty()))
			return candidate.exclusions().isEmpty()
					? new PackAbsence(Kind.NOTHING_FOUND, "Nothing to sync: the scan found no publishable file for any of the " + manifest.groups().size()
							+ " configured groups (" + String.join(", ", manifest.groups().keySet()) + "). Install mods on the server, or check the from-server rules and the group directories under host-modpack/.")
					: new PackAbsence(Kind.ALL_EXCLUDED, "Nothing to sync: all " + candidate.exclusions().size()
							+ " files the scan found were excluded from the modpack. Check the exclude rules; the AutoModpack jar and server-side-only mods are never published.");
		return absenceOf(manifest);
	}

	/**
	 * Why a generation would hand no default client any file, or null when it serves one. The selection story is the
	 * same for a refused publication and a loaded journal, so both verdicts come from here: the flags decide who
	 * receives the pack, and only the files decide whether that reception is empty - an empty group holding the flags
	 * satisfies nobody.
	 */
	static PackAbsence absenceOf(GroupManifest manifest) {
		if (manifest.groups().values().stream().allMatch(group -> group.files().isEmpty()))
			return new PackAbsence(Kind.NOTHING_FOUND, "Nothing to sync: the published generation holds no files for any of the " + manifest.groups().size()
					+ " configured groups (" + String.join(", ", manifest.groups().keySet()) + ").");
		if (manifest.groups().values().stream().noneMatch(group -> (group.required() || group.defaultSelected()) && !group.files().isEmpty()))
			return new PackAbsence(Kind.NOTHING_SELECTED, "Nothing to sync: the modpack holds " + fileCount(manifest)
					+ " files, but no group that is required or selected by default carries any of them. Mark a group that carries files required or selected by default.");
		return null;
	}

	/**
	 * A group that is required or selected by default and carries no files is dead configuration: every default client
	 * is handed it and receives nothing through it. It is a receipt, not a refusal, because the operator may be
	 * mid-edit and other groups may still carry the pack.
	 */
	private static void warnUnusableSelectedGroups(GroupManifest manifest) {
		for (var entry : manifest.groups().entrySet())
			if (entry.getValue().files().isEmpty() && (entry.getValue().required() || entry.getValue().defaultSelected()))
				LOGGER.warn("Group '{}' is required or selected by default but carries no files; a default client receives nothing through it", entry.getKey());
	}

	private static int fileCount(GroupManifest manifest) {
		int files = 0;
		for (var group : manifest.groups().values()) files += group.files().size();
		return files;
	}

	/**
	 * Hosting follows the committed generation of every outcome that carries one, bound once here inside the operation lease instead of remembered per code path; a failed swap is reported on the committed outcome, never
	 * as a rejection of a durable commit. Binding a generation is also what clears the pack absence: a bound host has something to serve.
	 */
	private <R extends HostingOutcome> R bindHosting(R result) {
		if (!(result instanceof CommittedOutcome committed)) return result;
		packAbsence = null;
		R bound = result;
		try {
			hostingBinder.bind(committed.hosting());
		} catch (Exception e) {
			LOGGER.error("The generation committed, but the hosting swap failed", e);
			@SuppressWarnings("unchecked")
			R failed = (R) committed.withHostingFailure(e);
			bound = failed;
		}
		autoExportHttp(committed.hosting());
		return bound;
	}

	/** Publish-time mirror of the URL contract for static hosting; a failed or refused export is logged loudly but never fails the committed publication. */
	private void autoExportHttp(GenerationHosting hosting) {
		ServerConfigJsons.ServerConfigFieldsV3 serverConfig = config.get();
		String directory = serverConfig == null || serverConfig.exportHttpDirectory == null ? "" : serverConfig.exportHttpDirectory.trim();
		if (directory.isEmpty()) return;
		try {
			HttpExporter.Result result = httpExporter.exportLeased(Path.of(directory), false, hosting);
			if (result instanceof HttpExporter.Result.Exported exported) LOGGER.info(exported.receipt(directory));
			else if (result instanceof HttpExporter.Result.Rejected refused) LOGGER.warn("Refused to export the HTTP contract tree to {}: {}", directory, refused.detail());
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

	private void validateConfiguration() throws CandidateBuildException {
		ServerConfigJsons.ServerConfigFieldsV3 serverConfig = config.get();
		if (serverConfig == null || serverConfig.modpack.categories.isEmpty())
			throw new CandidateBuildException("Server group configuration is missing");
		for (var categoryEntry : serverConfig.modpack.categories.entrySet()) {
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
		ServerConfigJsons.ServerConfigFieldsV3 serverConfig = config.get();
		Map<String, Path> groupDirectories = new TreeMap<>();
		for (var categoryEntry : serverConfig.modpack.categories.entrySet()) {
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
		return locks.isPublishing();
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
			int files = fileCount(candidate.manifest());
			return new CandidateSummary(candidate.manifest().groups().size(), files, candidate.objects().size(), candidate.exclusions(), diff.summary());
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

	/** The operation produced no generation; the detail explains the refusal or failure. Shared by every operation's outcome. */
	public record Rejected(String detail, Throwable cause) implements PreviewResult, RevertResult, PublishResult, LoadResult {
		public Rejected {
			detail = Objects.requireNonNull(detail);
		}
	}

	public sealed interface PreviewResult permits PreviewReady, Rejected {}

	/**
	 * A ready preview, carrying the absence that would refuse its publication. The preview still answers "what would
	 * this scan produce", so an unpublishable candidate previews truthfully and the caller says so next to it.
	 */
	public record PreviewReady(CandidateState state, Optional<PackAbsence> absence) implements PreviewResult {
		public PreviewReady {
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(absence, "absence");
			if (state.patchNotesSource().isEmpty()) throw new IllegalArgumentException("Preview result requires a resolved patch-note source");
		}
	}

	public sealed interface RevertResult extends HostingOutcome permits Reverted, Rejected {}

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

	public sealed interface PublishResult extends HostingOutcome permits Published, NoChanges, PublishResult.NothingToPublish, Rejected {

		/**
		 * The candidate was a truthful scan of the server and must not be published; the absence says which way, in
		 * the words that fix it. This is a state, not a failure, so the boot logs it and keeps the world running.
		 */
		record NothingToPublish(PackAbsence absence) implements PublishResult {
			public NothingToPublish {
				absence = Objects.requireNonNull(absence, "absence");
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

	public sealed interface LoadResult extends HostingOutcome permits Loaded, LoadResult.NothingPublished, Rejected {

		/** No generation was ever published, which is the same state a refused publication leaves behind. */
		record NothingPublished(PackAbsence absence) implements LoadResult {
			public NothingPublished {
				absence = Objects.requireNonNull(absence, "absence");
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
