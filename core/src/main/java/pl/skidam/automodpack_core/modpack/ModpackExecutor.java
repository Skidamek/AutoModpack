package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.*;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import pl.skidam.automodpack_core.config.ServerConfigJsons;
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
import pl.skidam.automodpack_core.platforms.PlatformSourceLookup;
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
	private final Supplier<ServerConfigJsons.ServerConfigFieldsV3> config;
	private final PlatformSourceLookup platformSourceLookup;

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

	ModpackExecutor(Path serverRoot, Path groupRoot, Path generationRoot, DataRootResolver.Location dataLocation, CandidateScan candidateScan,
			ThreadPoolExecutor creationExecutor) {
		this(serverRoot, groupRoot, generationRoot, new Deps(new GenerationStore(generationRoot, dataLocation.layout().objectsDirectory(), dataLocation), candidateScan, creationExecutor));
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
		this.platformSourceLookup = Objects.requireNonNull(deps.platformSourceLookup());
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

	/**
	 * Writes the URL-contract tree (head, journal, objects/&lt;sha1&gt;) as byte-for-byte copies of the hosted files, ready for
	 * any static HTTPS host. Objects the platforms still serve themselves are pruned unless {@code includeAll} or
	 * {@code exportHttpIncludeAll} keeps them as the host-side backstop; the returned receipt carries the counts. Nothing
	 * already in the target directory is ever deleted, so stale objects from old generations may accumulate there; the
	 * operator owns the directory. Orthogonal to hosting: works in every connection mode.
	 */
	public ExportHttpResult exportHttp(Path targetDirectory) throws IOException {
		return exportHttp(targetDirectory, false);
	}

	public ExportHttpResult exportHttp(Path targetDirectory, boolean includeAll) throws IOException {
		ServerConfigJsons.ServerConfigFieldsV3 serverConfig = config.get();
		if (serverConfig != null && serverConfig.validateSecrets)
			return new ExportHttpResult.Rejected("The pack validates download secrets, which a public mirror cannot enforce");
		Path target = (targetDirectory.isAbsolute() ? targetDirectory : serverRoot.resolve(targetDirectory)).normalize();
		boolean exportEverything = includeAll || serverConfig != null && serverConfig.exportHttpIncludeAll;
		// The platform round-trip hashes every object and can spend seconds on the network, so the manual export
		// resolves it off the lease: holding the publication lease that long would reject concurrent publishes for
		// no correctness gain. Each attempt re-verifies under the lease that the resolved snapshot is still the live
		// generation - a publish that slipped in costs a re-resolve - and a second lost race falls back to the
		// fully-leased export, which cannot lose.
		for (int attempt = 0; attempt < 2; attempt++) {
			GenerationHosting hosting = generationStore.hosting();
			Map<String, Long> platformServed = resolvePlatformServed(hosting, exportEverything);
			OperationLease operation = acquire(false);
			if (operation == null) return new ExportHttpResult.Rejected("Another modpack operation is already in progress");
			try (operation) {
				if (generationStore.hosting().asMap().equals(hosting.asMap()))
					return exportCopied(target, exportEverything, hosting, platformServed);
			}
		}
		OperationLease operation = acquire(false);
		if (operation == null) return new ExportHttpResult.Rejected("Another modpack operation is already in progress");
		try (operation) {
			GenerationHosting hosting = generationStore.hosting();
			return exportCopied(target, exportEverything, hosting, resolvePlatformServed(hosting, exportEverything));
		}
	}

	/** Requires the caller to hold an operation lease: the journal is appended in place during a publish, so a lease-free export can copy a torn one. */
	private ExportHttpResult exportHttpLeased(Path targetDirectory, boolean includeAll) throws IOException {
		ServerConfigJsons.ServerConfigFieldsV3 serverConfig = config.get();
		if (serverConfig != null && serverConfig.validateSecrets)
			return new ExportHttpResult.Rejected("The pack validates download secrets, which a public mirror cannot enforce");
		Path target = (targetDirectory.isAbsolute() ? targetDirectory : serverRoot.resolve(targetDirectory)).normalize();
		boolean exportEverything = includeAll || serverConfig != null && serverConfig.exportHttpIncludeAll;
		// The auto-export runs inside the publication lease, where nothing can slip in mid-export: the resolve may
		// simply run where it is, and the copy sees one consistent generation.
		GenerationHosting hosting = generationStore.hosting();
		return exportCopied(target, exportEverything, hosting, resolvePlatformServed(hosting, exportEverything));
	}

	/**
	 * The platform's served sizes for the snapshot's objects, asked with {@code exportEverything} as the off switch;
	 * a failed round-trip exports every object.
	 */
	private Map<String, Long> resolvePlatformServed(GenerationHosting hosting, boolean exportEverything) throws IOException {
		Map<String, Path> objects = new TreeMap<>();
		for (String key : hosting.asMap().keySet()) {
			if (isReservedDocument(key)) continue;
			if (!HashUtils.isSha1(key)) throw new IOException("Unexpected hosting key in the generation store: " + key);
			objects.put(HashUtils.normalizeSha1(key), hosting.get(key));
		}
		if (exportEverything || objects.isEmpty()) return Map.of();
		List<PlatformSourceLookup.Query> queries = new ArrayList<>();
		for (Map.Entry<String, Path> object : objects.entrySet())
			queries.add(new PlatformSourceLookup.Query(object.getKey(), Files.size(object.getValue()), object.getValue()));
		try {
			Map<String, Long> resolved = platformSourceLookup.platformSizes(queries);
			return resolved != null ? resolved : Map.of();
		} catch (RuntimeException e) {
			LOGGER.warn("Platform source resolution failed; exporting every object", e);
			return Map.of();
		}
	}

	/** Copies the snapshot's tree into the target: objects first, then journal, then the head - the commit pointer lands last. */
	private ExportHttpResult exportCopied(Path target, boolean exportEverything, GenerationHosting hosting, Map<String, Long> platformServed) throws IOException {
		int written = 0, omitted = 0, unresolvable = 0;
		for (Map.Entry<String, Path> entry : hosting.asMap().entrySet()) {
			String key = entry.getKey();
			// The reserved documents are written after every object, in Phase-A order below: a tree published by a
			// copy tool (aws s3 sync, rclone) serves its keys in write order, and the head is the commit pointer.
			if (isReservedDocument(key)) continue;
			Path destination;
			{
				String sha1 = HashUtils.normalizeSha1(key);
				Long served = platformServed.get(sha1);
				if (served != null && served.longValue() == Files.size(entry.getValue())) {
					omitted++;
					continue;
				}
				if (!exportEverything) unresolvable++;
				destination = target.resolve("objects").resolve(sha1);
			}
			Files.createDirectories(destination.getParent());
			// Objects are immutable and named by their hash, so an already-present file of any size is the same bytes
			// and the copy is skipped. Documents are the opposite: fixed-shape bodies whose bytes change while their
			// size stays, and they are the one file whose freshness the mirror exists to serve - always re-exported.
			if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) && Files.size(destination) == Files.size(entry.getValue())) {
				written++;
				continue;
			}
			copyAtomically(entry.getValue(), destination);
			written++;
		}
		// Documents land after every object, journal before head: a bucket synced with aws s3 sync or rclone serves
		// its keys in write order, so a client can never see the new head beside the old journal - the head is the
		// commit pointer of the whole tree and lands last.
		for (String key : new String[]{GenerationHosting.JOURNAL_KEY, GenerationHosting.HEAD_DOCUMENT_KEY}) {
			Path source = hosting.get(key);
			if (source == null) continue;
			copyAtomically(source, target.resolve(key));
			written++;
		}
		return new ExportHttpResult.Exported(written, omitted, unresolvable);
	}

	/** Publishes one exported file through a same-directory temporary and an atomic move, so a static host never serves a half-written copy. */
	private static void copyAtomically(Path source, Path destination) throws IOException {
		Path temporary = Files.createTempFile(destination.getParent(), ".export-", null);
		try {
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/** head/journal: served beside the content-addressed objects, exported to the target root, never pruned. The waiting track is an object like any other. */
	private static boolean isReservedDocument(String key) {
		return key.equals(GenerationHosting.HEAD_DOCUMENT_KEY) || key.equals(GenerationHosting.JOURNAL_KEY);
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
			try (ModpackCandidate candidate = buildCandidate(current, true);
					FileCache fileCache = FileCache.open(dataLayout.fileCacheDirectory())) {
				GenerationDiff diff = GenerationDiff.between(current == null ? null : current.manifest(), candidate.manifest());
				String token = ContentTree.tokenOf(candidate.manifest());
				CandidateState candidateState = candidateState(current, candidate, token, diff, Optional.empty());
				if (expectedContentToken != null && !expectedContentToken.equals(token))
					return new PublishResult.Rejected("Fresh candidate content does not match the requested guard", null);

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
		return new PackDocument(current.manifest(), current.contentToken(), current.policySha1(), current.createdAt(), current.ledger(), "");
	}

	private PackDocument currentDocument(GenerationStore.Publication publication) {
		return new PackDocument(publication.manifest(), publication.entry().contentToken(), publication.entry().policySha1(), publication.entry().createdAt(), publication.ledger(), "");
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
					serverConfig.autoExcludeServerSideMods, generationRoot.resolve(SERVER_STAGING_DIR.getFileName()), creationExecutor,
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

	/** Publish-time mirror of the URL contract for static hosting; a failed or refused export is logged loudly but never fails the committed publication. */
	private void autoExportHttp() {
		ServerConfigJsons.ServerConfigFieldsV3 serverConfig = config.get();
		String directory = serverConfig == null || serverConfig.exportHttpDirectory == null ? "" : serverConfig.exportHttpDirectory.trim();
		if (directory.isEmpty()) return;
		try {
			ExportHttpResult result = exportHttpLeased(Path.of(directory), false);
			if (result instanceof ExportHttpResult.Exported exported) LOGGER.info(exported.receipt(directory));
			else if (result instanceof ExportHttpResult.Rejected refused) LOGGER.warn("Refused to export the HTTP contract tree to {}: {}", directory, refused.detail());
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

	public sealed interface ExportHttpResult permits ExportHttpResult.Exported, ExportHttpResult.Rejected {

		record Exported(int exportedCount, int omittedCount, int unresolvableCount) implements ExportHttpResult {
			public Exported {
				if (exportedCount < 0 || omittedCount < 0 || unresolvableCount < 0) throw new IllegalArgumentException("Negative export count");
			}

			public String receipt(String directory) {
				String breakdown = omittedCount == 0 && unresolvableCount == 0
						? ""
						: " (" + omittedCount + " objects omitted: served by Modrinth/CurseForge; " + unresolvableCount + " unresolvable → included)";
				return "Exported " + exportedCount + " files to " + directory + breakdown;
			}
		}

		/** The export produced no tree; the detail explains the refusal. */
		record Rejected(String detail) implements ExportHttpResult {
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
