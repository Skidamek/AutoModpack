package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/**
 * Offline integrity inspection and repair for the active, locally installed generation.
 * The installed generation record is the expected truth; every filesystem and CAS
 * observation is force-rehashed before it can become a repair source.
 */
public final class OfflineRepair {
	private static final Comparator<Finding> FINDING_ORDER = Comparator.comparing((Finding finding) -> finding.place().ordinal()).thenComparing(Finding::logicalPath);
	private static final Comparator<EditableResetCandidate> EDITABLE_ORDER = Comparator.comparing(EditableResetCandidate::logicalPath);
	private final ClientStorage storage;

	public OfflineRepair(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage, "client storage");
	}

	public enum Place {
		CAS,
		PROJECTION,
		LIVE,
		GENERATED_COPY
	}

	public enum Condition {
		MISSING,
		DAMAGED,
		UNSUPPORTED_PATH
	}

	public record Request(SelectedModpackTarget activeTarget, Set<String> forceCopyPaths, Path protectedModPath) {
		public Request {
			activeTarget = Objects.requireNonNull(activeTarget, "active target");
			TreeSet<String> normalizedForceCopyPaths = new TreeSet<>();
			for (String path : Objects.requireNonNull(forceCopyPaths, "force-copy paths")) normalizedForceCopyPaths.add(UpdatePlanner.normalize(path));
			forceCopyPaths = Set.copyOf(normalizedForceCopyPaths);
			protectedModPath = protectedModPath == null ? null : protectedModPath.toAbsolutePath().normalize();
		}
	}

	public record Finding(Place place, String logicalPath, String expectedHash, long expectedSize, Condition condition, String observedHash, long observedSize,
			boolean locallyRepairable) {
		public Finding {
			place = Objects.requireNonNull(place, "repair place");
			logicalPath = Objects.requireNonNull(logicalPath, "logical path");
			expectedHash = HashUtils.normalizeSha1(expectedHash);
			if (expectedSize < 0 || observedSize < -1) throw new IllegalArgumentException("Repair finding sizes are invalid");
			condition = Objects.requireNonNull(condition, "repair condition");
			if (observedHash != null) observedHash = HashUtils.normalizeSha1(observedHash);
		}
	}

	public record EditableResetCandidate(String logicalPath, String defaultHash, long defaultSize, String currentHash, long currentSize, boolean absent) {
		public EditableResetCandidate {
			logicalPath = UpdatePlanner.normalize(logicalPath);
			defaultHash = HashUtils.normalizeSha1(defaultHash);
			if (defaultSize < 0 || currentSize < -1) throw new IllegalArgumentException("Editable reset candidate sizes are invalid");
			if (currentHash != null) currentHash = HashUtils.normalizeSha1(currentHash);
			if (absent != (currentHash == null)) throw new IllegalArgumentException("Editable reset candidate absence is inconsistent");
		}
	}

	public record Prepared(String modpackId, String generationId, String selectionDigest, Request request, List<Finding> findings,
			List<EditableResetCandidate> editableResetCandidates, List<String> unownedModPaths, long directlyHashedFileCount, long directlyHashedBytes) {
		public Prepared {
			Objects.requireNonNull(modpackId, "modpack ID");
			generationId = HashUtils.normalizeSha1(generationId);
			selectionDigest = HashUtils.normalizeSha1(selectionDigest);
			request = Objects.requireNonNull(request, "repair request");
			findings = List.copyOf(findings);
			editableResetCandidates = List.copyOf(editableResetCandidates);
			unownedModPaths = List.copyOf(unownedModPaths);
			if (directlyHashedFileCount < 0 || directlyHashedBytes < 0) throw new IllegalArgumentException("Direct hash receipt is invalid");
		}

		public boolean healthy() {
			return findings.isEmpty();
		}

		public boolean requiresUpdate() {
			return findings.stream().anyMatch(finding -> !finding.locallyRepairable());
		}
	}

	public record Receipt(Prepared before, Prepared after, long repairedCasObjects, long repairedMaterializedFiles, long resetEditableFiles, long archivedUnownedMods) {
		public Receipt {
			before = Objects.requireNonNull(before, "repair input");
			after = Objects.requireNonNull(after, "repair result");
			if (repairedCasObjects < 0 || repairedMaterializedFiles < 0 || resetEditableFiles < 0 || archivedUnownedMods < 0)
				throw new IllegalArgumentException("Repair receipt values are invalid");
		}

		public boolean complete() {
			return after.healthy();
		}

		public boolean changedFiles() {
			return repairedMaterializedFiles > 0 || resetEditableFiles > 0 || archivedUnownedMods > 0;
		}
	}

	/** Performs a read-only, cache-bypassing inspection. */
	public Prepared inspect(Request request) throws IOException {
		try (FileMetadataCache fileCache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			return analyze(request, fileCache).prepared();
		}
	}

	/** Resumes a power-interrupted repair from its durable intent, if one exists. */
	public Optional<Receipt> recover(Request request) throws IOException {
		return ClientStorageMutation.run(storage, () -> recoverLocked(request));
	}

	private Optional<Receipt> recoverLocked(Request request) throws IOException {
		if (!Files.exists(storage.repairJournalFile(), LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		try (FileMetadataCache fileCache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			Analysis current = analyze(request, fileCache);
			ClientStorageJsons.OfflineRepairJournalFields journal = readJournal(current.prepared());
			return Optional.of(executeJournal(current.prepared(), current, journal, fileCache));
		}
	}

	/** Repairs every locally repairable non-editable finding and returns a fresh direct-hash receipt. */
	public Receipt apply(Prepared prepared) throws IOException {
		return apply(prepared, Set.of(), Set.of());
	}

	/** Applies local repairs plus the exact editable resets and unowned-mod cleanup selected by the player. */
	public Receipt apply(Prepared prepared, Set<String> editableResetPaths, Set<String> unownedModPaths) throws IOException {
		return ClientStorageMutation.run(storage, () -> applyLocked(prepared, editableResetPaths, unownedModPaths));
	}

	private Receipt applyLocked(Prepared prepared, Set<String> editableResetPaths, Set<String> unownedModPaths) throws IOException {
		Objects.requireNonNull(prepared, "prepared repair");
		Set<String> requestedEditableResets = normalizedSelection(editableResetPaths);
		Set<String> requestedUnownedMods = normalizedSelection(unownedModPaths);
		Request request = prepared.request();
		try (FileMetadataCache fileCache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			Analysis current = analyze(request, fileCache);
			requireSamePinnedIdentity(prepared, current.prepared());
			requireSelections(current.prepared(), requestedEditableResets, requestedUnownedMods);
			ClientStorageJsons.OfflineRepairJournalFields journal = createJournal(current, requestedEditableResets, requestedUnownedMods);
			ConfigTools.writeAtomic(storage.repairJournalFile(), journal);
			ClientObjectStore.publishOwnership(storage);
			return executeJournal(prepared, current, journal, fileCache);
		}
	}

	private Receipt executeJournal(Prepared prepared, Analysis current, ClientStorageJsons.OfflineRepairJournalFields journal, FileMetadataCache fileCache)
			throws IOException {
		RepairCounts repaired = repairLocally(current, fileCache);
		long resetEditable = resetJournalEditable(current.prepared().request(), journal, fileCache);
		long archivedUnowned = archiveJournalUnowned(current.prepared().request(), journal, fileCache);
		Prepared after = analyze(current.prepared().request(), fileCache).prepared();
		requireSamePinnedIdentity(prepared, after);
		Files.deleteIfExists(storage.repairJournalFile());
		FileTrees.forceDirectory(storage.clientDirectory());
		ClientObjectStore.publishOwnership(storage);
		return new Receipt(current.prepared(), after, repaired.casObjects(), repaired.materializedFiles(), resetEditable, archivedUnowned);
	}

	private RepairCounts repairLocally(Analysis current, FileMetadataCache fileCache) throws IOException {
		Request request = current.prepared().request();
		long repairedCas = 0;
		for (Expected expected : current.expected().values().stream().filter(value -> value.place() == Place.CAS).sorted(Expected.ORDER).toList()) {
			Observation observation = current.observations().get(expected.path());
			if (matches(observation, expected.content()) || observation != null && observation.unsupported()) continue;
			Path source = verifiedSource(current.sources().getOrDefault(expected.content(), List.of()), expected.path(), expected.content(), fileCache);
			if (source == null) continue;
			assertPinned(request);
			requireSafePath(storage.objectsDirectory(), expected.path());
			if (VerifiedFileTransfer.copyAtomicImmutable(source, expected.path(), expected.content().size(), expected.content().hash(), fileCache)) repairedCas++;
			fileCache.rehash(expected.path());
		}

		Analysis withRepairedCas = analyze(request, fileCache);
		long repairedFiles = 0;
		for (Expected expected : withRepairedCas.expected().values().stream().filter(value -> value.place() != Place.CAS).sorted(Expected.ORDER).toList()) {
			Observation observation = withRepairedCas.observations().get(expected.path());
			if (matches(observation, expected.content()) || observation != null && observation.unsupported()) continue;
			Path object = storage.objectFile(expected.content().hash()).normalize();
			Observation objectObservation = withRepairedCas.observations().get(object);
			if (!matches(objectObservation, expected.content())) continue;
			assertPinned(request);
			requireSafePath(expected.root(), expected.path());
			boolean repaired = expected.place() == Place.PROJECTION
					? VerifiedFileTransfer.linkAtomic(object, expected.path(), expected.content().size(), expected.content().hash(), fileCache)
					: VerifiedFileTransfer.copyAtomic(object, expected.path(), expected.content().size(), expected.content().hash(), fileCache);
			if (repaired) repairedFiles++;
			fileCache.rehash(expected.path());
		}
		return new RepairCounts(repairedCas, repairedFiles);
	}

	private ClientStorageJsons.OfflineRepairJournalFields createJournal(Analysis analysis, Set<String> editableResetPaths, Set<String> unownedModPaths) throws IOException {
		ClientStorageJsons.OfflineRepairJournalFields journal = new ClientStorageJsons.OfflineRepairJournalFields();
		journal.modpackId = analysis.prepared().modpackId();
		journal.generationId = analysis.prepared().generationId();
		journal.selectionDigest = analysis.prepared().selectionDigest();
		Map<String, EditableResetCandidate> editable = analysis.prepared().editableResetCandidates().stream()
				.collect(Collectors.toMap(EditableResetCandidate::logicalPath, candidate -> candidate));
		List<ClientStorageJsons.OfflineRepairJournalFields.EditableResetFields> resets = new ArrayList<>();
		for (String path : editableResetPaths.stream().sorted().toList()) {
			EditableResetCandidate candidate = editable.get(path);
			if (candidate == null) throw new IOException("Editable reset selection is stale: " + path);
			ClientStorageJsons.OfflineRepairJournalFields.EditableResetFields fields = new ClientStorageJsons.OfflineRepairJournalFields.EditableResetFields();
			fields.logicalPath = candidate.logicalPath();
			fields.defaultHash = candidate.defaultHash();
			fields.defaultSize = candidate.defaultSize();
			fields.currentHash = candidate.currentHash();
			fields.currentSize = candidate.currentSize();
			fields.absent = candidate.absent();
			resets.add(fields);
		}
		journal.editableResets = List.copyOf(resets);
		List<ClientStorageJsons.OfflineRepairJournalFields.UnownedModFields> unowned = new ArrayList<>();
		for (String path : unownedModPaths.stream().sorted().toList()) {
			Observation observation = analysis.observations().get(storage.gamePath(path).toAbsolutePath().normalize());
			if (observation == null || observation.unsupported()) throw new IOException("Unowned mod selection is stale: " + path);
			ClientStorageJsons.OfflineRepairJournalFields.UnownedModFields fields = new ClientStorageJsons.OfflineRepairJournalFields.UnownedModFields();
			fields.logicalPath = path;
			fields.objectHash = observation.hash();
			fields.size = observation.size();
			unowned.add(fields);
		}
		journal.unownedMods = List.copyOf(unowned);
		return journal;
	}

	private ClientStorageJsons.OfflineRepairJournalFields readJournal(Prepared prepared) throws IOException {
		Path path = storage.repairJournalFile();
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Offline repair journal is not a regular file: " + path);
		ClientStorageJsons.OfflineRepairJournalFields journal = ConfigTools.read(path, ClientStorageJsons.OfflineRepairJournalFields.class)
				.orElseThrow(() -> new IOException("Offline repair journal is empty: " + path));
		if (journal.schemaVersion != 1 || !prepared.modpackId().equals(journal.modpackId) || !prepared.generationId().equals(journal.generationId)
				|| !prepared.selectionDigest().equals(journal.selectionDigest) || journal.editableResets == null || journal.unownedMods == null)
			throw new IOException("Offline repair journal identity is invalid: " + path);
		List<String> editablePaths = journal.editableResets.stream().map(fields -> UpdatePlanner.normalize(fields.logicalPath)).toList();
		List<String> unownedPaths = journal.unownedMods.stream().map(fields -> UpdatePlanner.normalize(fields.logicalPath)).toList();
		if (!editablePaths.equals(editablePaths.stream().distinct().sorted().toList()) || !unownedPaths.equals(unownedPaths.stream().distinct().sorted().toList()))
			throw new IOException("Offline repair journal paths are not canonical: " + path);
		for (var fields : journal.editableResets)
			new EditableResetCandidate(fields.logicalPath, fields.defaultHash, fields.defaultSize, fields.currentHash, fields.currentSize, fields.absent);
		for (var fields : journal.unownedMods) {
			HashUtils.normalizeSha1(fields.objectHash);
			if (fields.size < 0) throw new IOException("Offline repair journal contains an invalid unowned mod size");
		}
		return journal;
	}

	private long resetJournalEditable(Request request, ClientStorageJsons.OfflineRepairJournalFields journal, FileMetadataCache fileCache) throws IOException {
		if (journal.editableResets.isEmpty()) return 0;
		TreeSet<String> tombstones = new TreeSet<>(storage.readOverlayState(journal.modpackId).deletedPaths);
		long reset = 0;
		for (var fields : journal.editableResets) {
			Path live = storage.gamePath(fields.logicalPath);
			Path object = storage.objectFile(fields.defaultHash).normalize();
			if (!FileIntegrity.matchesNamed(object, fields.defaultSize, fields.defaultHash, fileCache)) throw new IOException("Editable default is unavailable locally: " + fields.logicalPath);
			boolean alreadyReset = FileIntegrity.matches(live, fields.defaultSize, fields.defaultHash, fileCache);
			if (!alreadyReset) {
				if (fields.absent) {
					if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Editable file changed after repair was journaled: " + fields.logicalPath);
				} else if (!FileIntegrity.matches(live, fields.currentSize, fields.currentHash, fileCache)) {
					throw new IOException("Editable file changed after repair was journaled: " + fields.logicalPath);
				}
				assertPinned(request);
				if (!fields.absent)
					PreservationVault.preserve(storage, journal.modpackId, journal.generationId, PreservationVault.Reason.EDITABLE_RESET, Root.GAME_DIR, fields.logicalPath,
							fields.currentHash, fields.currentSize);
				requireSafePath(storage.gameDirectory(), live);
				VerifiedFileTransfer.copyAtomic(object, live, fields.defaultSize, fields.defaultHash, fileCache);
				fileCache.rehash(live);
				reset++;
			}
			Files.deleteIfExists(storage.overlayFile(journal.modpackId, fields.logicalPath));
			tombstones.remove(fields.logicalPath);
		}
		storage.writeOverlayState(journal.modpackId, tombstones);
		return reset;
	}

	private long archiveJournalUnowned(Request request, ClientStorageJsons.OfflineRepairJournalFields journal, FileMetadataCache fileCache) throws IOException {
		long archived = 0;
		for (var fields : journal.unownedMods) {
			Path source = storage.gamePath(fields.logicalPath);
			if (source.toAbsolutePath().normalize().equals(request.protectedModPath())) throw new IOException("The running AutoModpack JAR cannot be archived");
			if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
				boolean preserved = PreservationVault.read(storage, journal.modpackId).claims().stream().anyMatch(claim -> claim.reason() == PreservationVault.Reason.STRICT_REPAIR
						&& claim.sourceRoot() == Root.GAME_DIR && claim.originalPath().equals(fields.logicalPath) && claim.objectHash().equals(fields.objectHash) && claim.size() == fields.size);
				if (!preserved) throw new IOException("Journaled unowned mod disappeared before it was preserved: " + fields.logicalPath);
				continue;
			}
			requireSafePath(storage.modsDirectory(), source);
			if (!FileIntegrity.matches(source, fields.size, fields.objectHash, fileCache)) throw new IOException("Unowned mod changed after repair was journaled: " + fields.logicalPath);
			assertPinned(request);
			PreservationVault.preserveAndRemove(storage, journal.modpackId, journal.generationId, PreservationVault.Reason.STRICT_REPAIR, Root.GAME_DIR, fields.logicalPath,
					fields.objectHash, fields.size);
			archived++;
		}
		return archived;
	}

	private static Set<String> normalizedSelection(Set<String> paths) {
		TreeSet<String> normalized = new TreeSet<>();
		for (String path : Objects.requireNonNull(paths, "repair selection")) normalized.add(UpdatePlanner.normalize(path));
		return Set.copyOf(normalized);
	}

	private static void requireSelections(Prepared prepared, Set<String> editable, Set<String> unowned) throws IOException {
		Set<String> editableCandidates = prepared.editableResetCandidates().stream().map(EditableResetCandidate::logicalPath).collect(Collectors.toSet());
		if (!editableCandidates.containsAll(editable)) throw new IOException("Editable reset selection contains a stale path");
		if (!Set.copyOf(prepared.unownedModPaths()).containsAll(unowned)) throw new IOException("Unowned-mod selection contains a stale path");
	}

	private Analysis analyze(Request request, FileMetadataCache fileCache) throws IOException {
		Objects.requireNonNull(request, "repair request");
		assertPinned(request);
		Map<Path, Expected> expected = new LinkedHashMap<>();
		Map<Path, Observation> observations = new HashMap<>();
		Map<String, EditableResetCandidate> editable = new TreeMap<>();
		String modpackId = request.activeTarget().manifest().modpackId();
		String generationId = request.activeTarget().generationTarget().targetGenerationId();

		// The state reader validates editable tombstone identity and canonical paths.
		storage.readOverlayState(modpackId);
		Map<String, Observation> overlays = inspectOverlay(modpackId, fileCache, observations);
		for (var item : request.activeTarget().flatTarget().list.stream().sorted(Comparator.comparing(value -> UpdatePlanner.normalize(value.file))).toList()) {
			String logicalPath = UpdatePlanner.normalize(item.file);
			Content content = new Content(item.sha1, parseSize(item.size));
			addExpected(expected, new Expected(Place.CAS, logicalPath, storage.objectsDirectory(), storage.objectFile(content.hash()).normalize(), content));
			addExpected(expected, new Expected(Place.PROJECTION, logicalPath, storage.activeDirectory(), storage.activePath(logicalPath), content));

			Path livePath = storage.gamePath(logicalPath);
			if (item.editable) {
				Observation live = observe(livePath, storage.gameDirectory(), fileCache, observations);
				if (!matches(live, content))
					editable.put(logicalPath, new EditableResetCandidate(logicalPath, content.hash(), content.size(), live == null ? null : live.hash(), live == null ? -1 : live.size(), live == null));
				Observation overlay = overlays.get(logicalPath);
				if (overlay != null && !overlay.unsupported())
					addExpected(expected,
							new Expected(Place.CAS, logicalPath, storage.objectsDirectory(), storage.objectFile(overlay.hash()).normalize(), new Content(overlay.hash(), overlay.size())));
			} else if (!ModpackPathPolicy.isActiveMod(logicalPath, item.type) || request.forceCopyPaths().contains(logicalPath)) {
				addExpected(expected, new Expected(Place.LIVE, logicalPath, storage.gameDirectory(), livePath, content));
			}
		}

		String selectionDigest = UpdateTransaction.digest(request.activeTarget().selection().intent());
		GeneratedCopyState generated = GeneratedCopyState.read(storage, modpackId, generationId, selectionDigest);
		for (GeneratedCopyState.Entry entry : generated.entries()) {
			Content content = new Content(entry.sha1(), entry.size());
			addExpected(expected, new Expected(Place.CAS, entry.logicalPath(), storage.objectsDirectory(), storage.objectFile(content.hash()).normalize(), content));
			Path live = storage.gamePath(entry.logicalPath());
			addExpected(expected, new Expected(Place.GENERATED_COPY, entry.logicalPath(), storage.gameDirectory(), live, content));
		}
		addBaselineObjects(expected, modpackId);
		for (PreservationVault.Claim claim : PreservationVault.read(storage, modpackId).claims()) {
			Content content = new Content(claim.objectHash(), claim.size());
			addExpected(expected, new Expected(Place.CAS, claim.originalPath(), storage.objectsDirectory(), storage.objectFile(content.hash()).normalize(), content));
			Path sourceRoot = switch (claim.sourceRoot()) {
				case GAME_DIR -> storage.gameDirectory();
				case OVERLAY -> storage.overlayDirectory(modpackId);
				case PROJECTION -> storage.activeDirectory();
			};
			Path source = switch (claim.sourceRoot()) {
				case GAME_DIR -> storage.gamePath(claim.originalPath());
				case OVERLAY -> storage.overlayFile(modpackId, claim.originalPath());
				case PROJECTION -> storage.activePath(claim.originalPath());
			};
			observe(source, sourceRoot, fileCache, observations);
			Path savedRoot = storage.restoredClaimDirectory(modpackId, claim.generationId(), claim.claimId());
			observe(LogicalPath.resolve(savedRoot, claim.originalPath()), savedRoot, fileCache, observations);
		}

		Set<String> ownedLiveMods = new TreeSet<>();
		for (Expected value : expected.values()) if ((value.place() == Place.LIVE || value.place() == Place.GENERATED_COPY) && ModpackPathPolicy.isModPath(value.logicalPath())) ownedLiveMods.add(value.logicalPath());
		List<String> unownedMods = inspectMods(request.protectedModPath(), ownedLiveMods, fileCache, observations);
		for (Expected value : expected.values()) observe(value.path(), value.root(), fileCache, observations);

		Map<Content, List<Path>> sources = new HashMap<>();
		for (Observation observation : observations.values())
			if (!observation.unsupported()) sources.computeIfAbsent(new Content(observation.hash(), observation.size()), ignoredContent -> new ArrayList<>()).add(observation.path());
		for (List<Path> paths : sources.values()) paths.sort(Comparator.comparing(Path::toString));
		List<Finding> findings = new ArrayList<>();
		for (Expected value : expected.values()) {
			Observation observation = observations.get(value.path());
			if (matches(observation, value.content())) continue;
			Condition condition = observation == null ? Condition.MISSING : observation.unsupported() ? Condition.UNSUPPORTED_PATH : Condition.DAMAGED;
			boolean repairable = condition != Condition.UNSUPPORTED_PATH && sources.getOrDefault(value.content(), List.of()).stream().anyMatch(path -> !path.equals(value.path()));
			findings.add(new Finding(value.place(), value.logicalPath(), value.content().hash(), value.content().size(), condition,
					observation == null || observation.unsupported() ? null : observation.hash(), observation == null || observation.unsupported() ? -1 : observation.size(), repairable));
		}
		findings.sort(FINDING_ORDER);
		List<EditableResetCandidate> editableCandidates = editable.values().stream().sorted(EDITABLE_ORDER).toList();
		long bytes = 0;
		for (Observation observation : observations.values()) if (!observation.unsupported()) bytes = Math.addExact(bytes, observation.size());
		Prepared prepared = new Prepared(modpackId, generationId, selectionDigest, request, findings, editableCandidates, unownedMods,
				observations.values().stream().filter(observation -> !observation.unsupported()).count(), bytes);
		return new Analysis(prepared, Map.copyOf(expected), Map.copyOf(observations), immutableSources(sources));
	}

	private Map<String, Observation> inspectOverlay(String modpackId, FileMetadataCache fileCache, Map<Path, Observation> observations) throws IOException {
		Path root = storage.overlayDirectory(modpackId);
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Map.of();
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client editable overlay root is not a directory: " + root);
		Map<String, Observation> result = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.filter(candidate -> !candidate.equals(root)).sorted().toList()) {
				if (Files.isSymbolicLink(path)) throw new IOException("Client editable overlay contains a symbolic link: " + path);
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				String relative = UpdatePlanner.normalize(root.relativize(path).toString());
				Observation observation = observe(path, root, fileCache, observations);
				if (observation != null) result.put(relative, observation);
			}
		}
		return Map.copyOf(result);
	}

	private void addBaselineObjects(Map<Path, Expected> expected, String modpackId) throws IOException {
		Path baselineFile = storage.baselineFile(modpackId);
		if (!Files.exists(baselineFile, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isSymbolicLink(baselineFile) || !Files.isRegularFile(baselineFile, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client baseline is not a regular file: " + baselineFile);
		ClientStorageJsons.ClientBaselineFields baseline = ConfigTools.read(baselineFile, ClientStorageJsons.ClientBaselineFields.class)
				.orElseThrow(() -> new IOException("Client baseline is empty: " + baselineFile));
		if (baseline.schemaVersion != 1 || !modpackId.equals(baseline.modpackId) || baseline.entries == null) throw new IOException("Client baseline identity is invalid: " + baselineFile);
		for (var entry : baseline.entries) {
			if (entry == null || entry.logicalPath == null || entry.objectHash == null) throw new IOException("Client baseline entry is incomplete: " + baselineFile);
			String logicalPath = UpdatePlanner.normalize(entry.logicalPath);
			if (!logicalPath.equals(entry.logicalPath)) throw new IOException("Client baseline path is not canonical: " + entry.logicalPath);
			if (entry.absent) {
				if (!entry.objectHash.isEmpty() || entry.size != -1) throw new IOException("Absent client baseline contains file metadata: " + logicalPath);
				continue;
			}
			Content content = new Content(entry.objectHash, entry.size);
			addExpected(expected, new Expected(Place.CAS, logicalPath, storage.objectsDirectory(), storage.objectFile(content.hash()).normalize(), content));
		}
	}

	private List<String> inspectMods(Path protectedModPath, Set<String> ownedLiveMods, FileMetadataCache fileCache, Map<Path, Observation> observations) throws IOException {
		Path root = storage.modsDirectory();
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Mods path is not a directory: " + root);
		List<String> unowned = new ArrayList<>();
		try (Stream<Path> paths = Files.list(root)) {
			for (Path path : paths.sorted().toList()) {
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) continue;
				Path normalized = path.toAbsolutePath().normalize();
				String logicalPath = UpdatePlanner.normalize(storage.gameDirectory().relativize(normalized).toString());
				observe(normalized, storage.gameDirectory(), fileCache, observations);
				if (!ownedLiveMods.contains(logicalPath) && !normalized.equals(protectedModPath)) unowned.add(logicalPath);
			}
		}
		return List.copyOf(unowned);
	}

	private Observation observe(Path path, Path root, FileMetadataCache fileCache, Map<Path, Observation> observations) throws IOException {
		Path normalized = path.toAbsolutePath().normalize();
		if (observations.containsKey(normalized)) return observations.get(normalized);
		if (!safePath(root, normalized)) {
			Observation unsupported = new Observation(normalized, null, -1, true);
			observations.put(normalized, unsupported);
			return unsupported;
		}
		if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return null;
		if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
			Observation unsupported = new Observation(normalized, null, -1, true);
			observations.put(normalized, unsupported);
			return unsupported;
		}
		String hash = fileCache.hash(normalized);
		Observation observation = new Observation(normalized, hash, Files.size(normalized), false);
		observations.put(normalized, observation);
		return observation;
	}

	private void assertPinned(Request request) throws IOException {
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cannot repair while an update transaction is active");
		var state = storage.readActiveState();
		String modpackId = request.activeTarget().manifest().modpackId();
		String generationId = request.activeTarget().generationTarget().targetGenerationId();
		if (state == null || !modpackId.equals(state.modpackId) || !generationId.equals(state.generationId)) throw new IOException("Repair target is no longer the active installed generation");
		var stored = new ClientGenerationStore(storage).read(generationId).orElseThrow(() -> new IOException("Active client generation record is missing: " + generationId));
		if (!stored.equals(request.activeTarget().generationRecord())) throw new IOException("Repair target disagrees with the installed generation record");
		var active = new ClientGenerationStore(storage).readActiveTarget(request.activeTarget().platform()).orElseThrow(() -> new IOException("Active client target is unavailable"));
		if (!active.generationRecord().equals(request.activeTarget().generationRecord()) || !active.selection().intent().equals(request.activeTarget().selection().intent()))
			throw new IOException("Repair selection changed after preparation");
	}

	private static void requireSamePinnedIdentity(Prepared expected, Prepared actual) throws IOException {
		if (!expected.modpackId().equals(actual.modpackId()) || !expected.generationId().equals(actual.generationId()) || !expected.selectionDigest().equals(actual.selectionDigest()))
			throw new IOException("Active repair identity changed after preparation");
	}

	private static Path verifiedSource(Collection<Path> candidates, Path target, Content content, FileMetadataCache fileCache) throws IOException {
		for (Path candidate : candidates) {
			if (candidate.equals(target) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) continue;
			String hash = fileCache.hash(candidate);
			if (content.hash().equals(hash) && Files.size(candidate) == content.size()) return candidate;
		}
		return null;
	}

	private static void addExpected(Map<Path, Expected> expected, Expected candidate) throws IOException {
		Expected previous = expected.putIfAbsent(candidate.path(), candidate);
		if (previous != null && !previous.content().equals(candidate.content())) throw new IOException("Repair graph expects conflicting content at " + candidate.path());
	}

	private static boolean matches(Observation observation, Content content) {
		return observation != null && !observation.unsupported() && observation.size() == content.size() && observation.hash().equals(content.hash());
	}

	private static boolean safePath(Path root, Path path) {
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path normalizedPath = path.toAbsolutePath().normalize();
		if (!normalizedPath.startsWith(normalizedRoot) || Files.isSymbolicLink(normalizedRoot)) return false;
		Path current = normalizedRoot;
		for (Path part : normalizedRoot.relativize(normalizedPath)) {
			current = current.resolve(part);
			if (!current.equals(normalizedPath) && Files.isSymbolicLink(current)) return false;
		}
		return true;
	}

	private static void requireSafePath(Path root, Path path) throws IOException {
		if (!safePath(root, path) || Files.isSymbolicLink(path)) throw new IOException("Repair path contains a symbolic link or escapes its root: " + path);
	}

	private static long parseSize(String value) {
		try {
			long size = Long.parseLong(value);
			if (size < 0) throw new IllegalArgumentException("Negative size");
			return size;
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid installed file size: " + value, e);
		}
	}

	private static Map<Content, List<Path>> immutableSources(Map<Content, List<Path>> values) {
		Map<Content, List<Path>> result = new HashMap<>();
		values.forEach((content, paths) -> result.put(content, List.copyOf(paths)));
		return Map.copyOf(result);
	}

	private record Content(String hash, long size) {
		private Content {
			hash = HashUtils.normalizeSha1(hash);
			if (size < 0) throw new IllegalArgumentException("Repair content size is invalid");
		}
	}

	private record Expected(Place place, String logicalPath, Path root, Path path, Content content) {
		private static final Comparator<Expected> ORDER = Comparator.comparing((Expected value) -> value.place().ordinal()).thenComparing(Expected::logicalPath);
		private Expected {
			logicalPath = UpdatePlanner.normalize(logicalPath);
			root = root.toAbsolutePath().normalize();
			path = path.toAbsolutePath().normalize();
			if (!path.startsWith(root)) throw new IllegalArgumentException("Expected repair path escaped its root");
		}
	}

	private record Observation(Path path, String hash, long size, boolean unsupported) {}

	private record Analysis(Prepared prepared, Map<Path, Expected> expected, Map<Path, Observation> observations, Map<Content, List<Path>> sources) {}

	private record RepairCounts(long casObjects, long materializedFiles) {}
}
