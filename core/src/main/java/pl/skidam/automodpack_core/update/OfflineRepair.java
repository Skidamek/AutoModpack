package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Offline integrity inspection and repair for the active, locally installed generation.
 * The installed generation record is the expected truth; every filesystem and CAS
 * observation is force-rehashed before it can become a repair source.
 */
public final class OfflineRepair {
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
			for (String path : Objects.requireNonNull(forceCopyPaths, "force-copy paths")) normalizedForceCopyPaths.add(LogicalPath.normalize(path));
			forceCopyPaths = Set.copyOf(normalizedForceCopyPaths);
			protectedModPath = protectedModPath == null ? null : protectedModPath.toAbsolutePath().normalize();
		}
	}

	/** One read of the pinned generation identity; the per-row guards evaluate it instead of re-parsing the durable documents per repaired row. */
	private record PinnedGeneration(ClientStorageJsons.ClientGenerationStateFields state, SelectedModpackTarget activeTarget) {
		static PinnedGeneration read(ClientStorage storage, ClientPlatform platform) throws IOException {
			return new PinnedGeneration(storage.readActiveState(), new ClientGenerationStore(storage).readActiveTarget(platform).orElse(null));
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
			logicalPath = LogicalPath.normalize(logicalPath);
			defaultHash = HashUtils.normalizeSha1(defaultHash);
			if (defaultSize < 0 || currentSize < -1) throw new IllegalArgumentException("Editable reset candidate sizes are invalid");
			if (currentHash != null) currentHash = HashUtils.normalizeSha1(currentHash);
			if (absent != (currentHash == null)) throw new IllegalArgumentException("Editable reset candidate absence is inconsistent");
		}
	}

	public record Prepared(String modpackId, String contentToken, String selectionDigest, Request request, List<Finding> findings,
			List<EditableResetCandidate> editableResetCandidates, List<String> unownedModPaths, long directlyHashedFileCount, long directlyHashedBytes) {
		public Prepared {
			Objects.requireNonNull(modpackId, "modpack ID");
			contentToken = HashUtils.normalizeSha1(contentToken);
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
		try (FileCache fileCache = FileCache.open(storage.fileCacheDirectory())) {
			return analyze(request, fileCache).prepared();
		}
	}

	/** Resumes a power-interrupted repair from its durable intent, if one exists. */
	public Optional<Receipt> recover(Request request) throws IOException {
		return ClientStorageMutation.run(storage, () -> recoverLocked(request));
	}

	private Optional<Receipt> recoverLocked(Request request) throws IOException {
		if (!Files.exists(storage.repairJournalFile(), LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		try (FileCache fileCache = FileCache.open(storage.fileCacheDirectory())) {
			Analysis current = analyze(request, fileCache);
			ClientStorageJsons.OfflineRepairJournalFields journal = readJournal(current.prepared());
			if (journal == null) return Optional.empty();
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
		try (FileCache fileCache = FileCache.open(storage.fileCacheDirectory())) {
			Analysis current = analyze(request, fileCache);
			requireSamePinnedIdentity(prepared, current.prepared());
			requireSelections(current.prepared(), requestedEditableResets, requestedUnownedMods);
			ClientStorageJsons.OfflineRepairJournalFields journal = createJournal(current, requestedEditableResets, requestedUnownedMods);
			ConfigTools.writeAtomic(storage.repairJournalFile(), journal);
			ClientObjectStore.publishOwnership(storage);
			return executeJournal(prepared, current, journal, fileCache);
		}
	}

	private Receipt executeJournal(Prepared prepared, Analysis current, ClientStorageJsons.OfflineRepairJournalFields journal, FileCache fileCache)
			throws IOException {
		PinnedGeneration pinned = PinnedGeneration.read(storage, current.prepared().request().activeTarget().platform());
		Set<InstanceTree.Key> extra = new TreeSet<>(InstanceTree.Key.ORDER);
		for (var reset : journal.editableResets) extra.add(new InstanceTree.Key(Root.GAME_DIR, "", reset.logicalPath));
		for (var mod : journal.unownedMods) extra.add(new InstanceTree.Key(Root.GAME_DIR, "", mod.logicalPath));
		StateHistory.snapshotIfDirty(storage, extra, ClientStateJournal.Kind.LIVE, prepared.modpackId(), "repair");
		RepairCounts repaired = repairLocally(current, pinned, fileCache);
		int resetEdits = resetJournalEditable(current.prepared().request(), journal, pinned, fileCache);
		int archivedUnowned = archiveJournalUnowned(current.prepared().request(), journal, pinned, fileCache);
		Prepared after = analyze(current.prepared().request(), fileCache).prepared();
		requireSamePinnedIdentity(prepared, after);
		Files.deleteIfExists(storage.repairJournalFile());
		StateHistory.snapshotIfDirty(storage, extra, ClientStateJournal.Kind.REPAIR, prepared.modpackId(), "repair");
		FileTrees.forceDirectory(storage.clientDirectory());
		ClientObjectStore.publishOwnership(storage);
		return new Receipt(current.prepared(), after, repaired.casObjects(), repaired.materializedFiles(), resetEdits, archivedUnowned);
	}

	private RepairCounts repairLocally(Analysis current, PinnedGeneration pinned, FileCache fileCache) throws IOException {
		Request request = current.prepared().request();
		long repairedCas = 0;
		for (Expected expected : current.expected().values().stream().filter(value -> value.place() == Place.CAS).sorted(Expected.ORDER).toList()) {
			Observation observation = current.observations().get(expected.path());
			if (matches(observation, expected.content()) || observation != null && observation.unsupported()) continue;
			Path source = verifiedSource(current.sources().getOrDefault(expected.content(), List.of()), expected.path(), expected.content(), fileCache);
			if (source == null) continue;
			assertPinned(request, pinned);
			FileTrees.requireNoSymbolicLinkDescendants(storage.objectsDirectory(), expected.path(), "Repair object path");
			if (VerifiedFileTransfer.copyAtomicImmutable(source, expected.path(), expected.content().size(), expected.content().hash(), fileCache)) repairedCas++;
		}

		Analysis withRepairedCas = analyze(request, fileCache);
		long repairedFiles = 0;
		for (Expected expected : withRepairedCas.expected().values().stream().filter(value -> value.place() != Place.CAS).sorted(Expected.ORDER).toList()) {
			Observation observation = withRepairedCas.observations().get(expected.path());
			if (matches(observation, expected.content()) || observation != null && observation.unsupported()) continue;
			Path object = storage.objectFile(expected.content().hash()).normalize();
			Observation objectObservation = withRepairedCas.observations().get(object);
			if (!matches(objectObservation, expected.content())) continue;
			assertPinned(request, pinned);
			FileTrees.requireNoSymbolicLinkDescendants(expected.root(), expected.path(), "Repair path");
			boolean repaired = expected.place() == Place.PROJECTION
					? VerifiedFileTransfer.linkAtomic(object, expected.path(), expected.content().size(), expected.content().hash(), fileCache)
					: VerifiedFileTransfer.copyAtomic(object, expected.path(), expected.content().size(), expected.content().hash(), fileCache);
			if (repaired) repairedFiles++;
		}
		return new RepairCounts(repairedCas, repairedFiles);
	}

	private ClientStorageJsons.OfflineRepairJournalFields createJournal(Analysis analysis, Set<String> editableResetPaths, Set<String> unownedModPaths) throws IOException {
		ClientStorageJsons.OfflineRepairJournalFields journal = new ClientStorageJsons.OfflineRepairJournalFields();
		journal.modpackId = analysis.prepared().modpackId();
		journal.contentToken = analysis.prepared().contentToken();
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

	/**
	 * The durable resume intent, or null when none survives: a journal that cannot be understood is set aside as
	 * evidence and reads as none, so a torn repair journal can never block the boot. One that answers to another
	 * repair is stale state and still fails loudly.
	 */
	private ClientStorageJsons.OfflineRepairJournalFields readJournal(Prepared prepared) throws IOException {
		Path path = storage.repairJournalFile();
		ClientStorageJsons.OfflineRepairJournalFields journal = ConfigTools
				.readState(path, ClientStorageJsons.OfflineRepairJournalFields.class, "Offline repair journal", OfflineRepair::validatedJournal)
				.orElse(null);
		if (journal == null) return null;
		if (!prepared.modpackId().equals(journal.modpackId) || !prepared.contentToken().equals(journal.contentToken)
				|| !prepared.selectionDigest().equals(journal.selectionDigest))
			throw new IOException("Offline repair journal identity is invalid: " + path);
		return journal;
	}

	/** The repair journal's content contract; an unusable one is set aside as evidence by every reader. */
	static ClientStorageJsons.OfflineRepairJournalFields validatedJournal(ClientStorageJsons.OfflineRepairJournalFields fields) {
		if (fields.schemaVersion != 1 || fields.editableResets == null || fields.unownedMods == null) throw new IllegalArgumentException("Offline repair journal is incomplete");
		if (fields.editableResets.stream().anyMatch(Objects::isNull) || fields.unownedMods.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Offline repair journal contains incomplete rows");
		List<String> editablePaths = fields.editableResets.stream().map(reset -> LogicalPath.normalize(reset.logicalPath)).toList();
		List<String> unownedPaths = fields.unownedMods.stream().map(mod -> LogicalPath.normalize(mod.logicalPath)).toList();
		if (!editablePaths.equals(editablePaths.stream().distinct().sorted().toList()) || !unownedPaths.equals(unownedPaths.stream().distinct().sorted().toList()))
			throw new IllegalArgumentException("Offline repair journal paths are not canonical");
		for (var reset : fields.editableResets)
			new EditableResetCandidate(reset.logicalPath, reset.defaultHash, reset.defaultSize, reset.currentHash, reset.currentSize, reset.absent);
		for (var mod : fields.unownedMods) {
			HashUtils.normalizeSha1(mod.objectHash);
			if (mod.size < 0) throw new IllegalArgumentException("Offline repair journal contains an invalid unowned mod size");
		}
		return fields;
	}

	private int resetJournalEditable(Request request, ClientStorageJsons.OfflineRepairJournalFields journal, PinnedGeneration pinned, FileCache fileCache) throws IOException {
		int applied = 0;
		if (journal.editableResets.isEmpty()) return applied;
		TreeSet<String> tombstones = new TreeSet<>(storage.readOverlayState(journal.modpackId).deletedPaths);
		for (var fields : journal.editableResets) {
			Path live = storage.gamePath(fields.logicalPath);
			Path object = storage.objectFile(fields.defaultHash).normalize();
			if (!FileIntegrity.matchesNamed(object, fields.defaultSize, fields.defaultHash, fileCache)) throw new IOException("Editable default is unavailable locally: " + fields.logicalPath);
			boolean alreadyReset = FileIntegrity.matchesNamed(live, fields.defaultSize, fields.defaultHash, fileCache);
			if (!alreadyReset) {
				if (fields.absent) {
					if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Editable file changed after repair was journaled: " + fields.logicalPath);
				} else if (!FileIntegrity.matches(live, fields.currentSize, fields.currentHash, fileCache)) {
					throw new IOException("Editable file changed after repair was journaled: " + fields.logicalPath);
				}
				assertPinned(request, pinned);
				if (!fields.absent) {
					Path drifted = storage.objectFile(fields.currentHash).normalize();
					if (!FileIntegrity.matchesNamed(drifted, fields.currentSize, fields.currentHash, fileCache))
						VerifiedFileTransfer.copyAtomicImmutable(live, drifted, fields.currentSize, fields.currentHash, fileCache);
					FileTrees.requireNoSymbolicLinkDescendants(storage.gameDirectory(), live, "Repair path");
					VerifiedFileTransfer.copyAtomic(object, live, fields.defaultSize, fields.defaultHash, fileCache);
					applied++;
				}
			}
			Files.deleteIfExists(storage.overlayFile(journal.modpackId, fields.logicalPath));
			tombstones.remove(fields.logicalPath);
		}
		storage.writeOverlayState(journal.modpackId, tombstones);
		return applied;
	}

	private int archiveJournalUnowned(Request request, ClientStorageJsons.OfflineRepairJournalFields journal, PinnedGeneration pinned, FileCache fileCache) throws IOException {
		int applied = 0;
		if (journal.unownedMods.isEmpty()) return applied;
		for (var fields : journal.unownedMods) {
			Path source = storage.gamePath(fields.logicalPath);
			if (source.toAbsolutePath().normalize().equals(request.protectedModPath())) throw new IOException("The running AutoModpack JAR cannot be archived");
			if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
				if (!FileIntegrity.matchesNamed(storage.objectFile(fields.objectHash).normalize(), fields.size, fields.objectHash, fileCache))
					throw new IOException("Journaled unowned mod disappeared before its bytes were captured: " + fields.logicalPath);
				continue;
			}
			FileTrees.requireNoSymbolicLinkDescendants(storage.modsDirectory(), source, "Repair path");
			if (!FileIntegrity.matches(source, fields.size, fields.objectHash, fileCache)) throw new IOException("Unowned mod changed after repair was journaled: " + fields.logicalPath);
			assertPinned(request, pinned);
			Path object = storage.objectFile(fields.objectHash).normalize();
			if (!FileIntegrity.matchesNamed(object, fields.size, fields.objectHash, fileCache))
				VerifiedFileTransfer.copyAtomicImmutable(source, object, fields.size, fields.objectHash, fileCache);
			Files.delete(source);
			FileTrees.pruneEmptyAncestors(source, storage.modsDirectory());
			applied++;
		}
		return applied;
	}

	private static Set<String> normalizedSelection(Set<String> paths) {
		TreeSet<String> normalized = new TreeSet<>();
		for (String path : Objects.requireNonNull(paths, "repair selection")) normalized.add(LogicalPath.normalize(path));
		return Set.copyOf(normalized);
	}

	private static void requireSelections(Prepared prepared, Set<String> editable, Set<String> unowned) throws IOException {
		Set<String> editableCandidates = prepared.editableResetCandidates().stream().map(EditableResetCandidate::logicalPath).collect(Collectors.toSet());
		if (!editableCandidates.containsAll(editable)) throw new IOException("Editable reset selection contains a stale path");
		if (!Set.copyOf(prepared.unownedModPaths()).containsAll(unowned)) throw new IOException("Unowned-mod selection contains a stale path");
	}

	private Analysis analyze(Request request, FileCache fileCache) throws IOException {
		Objects.requireNonNull(request, "repair request");
		assertPinned(request, PinnedGeneration.read(storage, request.activeTarget().platform()));
		Map<Path, Expected> expected = new LinkedHashMap<>();
		Map<Path, Observation> observations = new HashMap<>();
		Map<Object, Observation> observationsByFileKey = new HashMap<>();
		Map<String, EditableResetCandidate> editable = new TreeMap<>();
		String modpackId = request.activeTarget().manifest().modpackId();
		String contentToken = request.activeTarget().packTarget().contentToken();

		// The state reader validates editable tombstone identity and canonical paths.
		ClientStorageJsons.ClientOverlayFields overlayState = storage.readOverlayState(modpackId);
		Map<String, Observation> overlays = inspectOverlay(modpackId, fileCache, observations, observationsByFileKey);
		for (var item : request.activeTarget().flatTarget().list.stream().sorted(Comparator.comparing(value -> LogicalPath.normalize(value.file))).toList()) {
			String logicalPath = LogicalPath.normalize(item.file);
			Content content = new Content(item.sha1, item.size);
			addExpected(expected, new Expected(Place.CAS, logicalPath, storage.objectsDirectory(), storage.objectFile(content.hash()).normalize(), content));
			addExpected(expected, new Expected(Place.PROJECTION, logicalPath, storage.activeDirectory(), storage.activePath(logicalPath), content));

			Path livePath = storage.gamePath(logicalPath);
			if (item.editable) {
				Observation live = observe(livePath, storage.gameDirectory(), fileCache, observations, observationsByFileKey);
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
		GeneratedCopyState generated = GeneratedCopyState.read(storage, modpackId, contentToken, selectionDigest);
		for (GeneratedCopyState.Entry entry : generated.entries()) {
			Content content = new Content(entry.sha1(), entry.size());
			addExpected(expected, new Expected(Place.CAS, entry.logicalPath(), storage.objectsDirectory(), storage.objectFile(content.hash()).normalize(), content));
			Path live = storage.gamePath(entry.logicalPath());
			addExpected(expected, new Expected(Place.GENERATED_COPY, entry.logicalPath(), storage.gameDirectory(), live, content));
		}

		Set<String> ownedLiveMods = new TreeSet<>();
		for (Expected value : expected.values()) if ((value.place() == Place.LIVE || value.place() == Place.GENERATED_COPY) && ModpackPathPolicy.isModPath(value.logicalPath())) ownedLiveMods.add(value.logicalPath());
		// Editable mod files keep a live copy in the game directory; until the player removes one, that copy belongs to the pack, not to the unowned-mod archive.
		for (var item : request.activeTarget().flatTarget().list) {
			String editablePath = LogicalPath.normalize(item.file);
			if (item.editable && !overlayState.deletedPaths.contains(editablePath) && ModpackPathPolicy.isActiveMod(editablePath, item.type)) ownedLiveMods.add(editablePath);
		}
		List<String> unownedMods = inspectMods(request.protectedModPath(), ownedLiveMods, fileCache, observations, observationsByFileKey);
		for (Expected value : expected.values()) observe(value.path(), value.root(), fileCache, observations, observationsByFileKey);

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
		List<EditableResetCandidate> editableCandidates = editable.values().stream().sorted(EDITABLE_ORDER).toList();
		long bytes = 0;
		for (Observation observation : observations.values()) if (!observation.unsupported()) bytes = Math.addExact(bytes, observation.size());
		Prepared prepared = new Prepared(modpackId, contentToken, selectionDigest, request, findings, editableCandidates, unownedMods,
				observations.values().stream().filter(observation -> !observation.unsupported()).count(), bytes);
		return new Analysis(prepared, Map.copyOf(expected), Map.copyOf(observations), immutableSources(sources));
	}

	private Map<String, Observation> inspectOverlay(String modpackId, FileCache fileCache, Map<Path, Observation> observations, Map<Object, Observation> observationsByFileKey) throws IOException {
		Path root = storage.overlayDirectory(modpackId);
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Map.of();
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client editable overlay root is not a directory: " + root);
		Map<String, Observation> result = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.filter(candidate -> !candidate.equals(root)).sorted().toList()) {
				if (Files.isSymbolicLink(path)) throw new IOException("Client editable overlay contains a symbolic link: " + path);
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				String relative = LogicalPath.normalize(root.relativize(path).toString());
				Observation observation = observe(path, root, fileCache, observations, observationsByFileKey);
				if (observation != null) result.put(relative, observation);
			}
		}
		return Map.copyOf(result);
	}

	private List<String> inspectMods(Path protectedModPath, Set<String> ownedLiveMods, FileCache fileCache, Map<Path, Observation> observations, Map<Object, Observation> observationsByFileKey) throws IOException {
		Path root = storage.modsDirectory();
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Mods path is not a directory: " + root);
		List<String> unowned = new ArrayList<>();
		try (Stream<Path> paths = Files.list(root)) {
			for (Path path : paths.sorted().toList()) {
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) continue;
				Path normalized = path.toAbsolutePath().normalize();
				String logicalPath = LogicalPath.normalize(storage.gameDirectory().relativize(normalized).toString());
				observe(normalized, storage.gameDirectory(), fileCache, observations, observationsByFileKey);
				if (!ownedLiveMods.contains(logicalPath) && !normalized.equals(protectedModPath)) unowned.add(logicalPath);
			}
		}
		return List.copyOf(unowned);
	}

	private Observation observe(Path path, Path root, FileCache fileCache, Map<Path, Observation> observations, Map<Object, Observation> observationsByFileKey) throws IOException {
		Path normalized = path.toAbsolutePath().normalize();
		if (observations.containsKey(normalized)) return observations.get(normalized);
		if (!FileTrees.hasNoSymbolicLinkDescendants(root, normalized)) {
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
		// The repair distrusts persisted hashes, but the projection is a hardlink twin of the CAS object on most
		// systems: the same bytes read twice. One fresh hash per distinct file this run is still a full check.
		// The twin's observation must also be recorded under this path, or the findings pass reads the path as missing.
		Object fileKey = fileKey(normalized);
		if (fileKey != null) {
			Observation seen = observationsByFileKey.get(fileKey);
			if (seen != null) {
				observations.put(normalized, seen);
				return seen;
			}
		}
		Observation observation = new Observation(normalized, fileCache.hash(normalized), Files.size(normalized), false, fileKey);
		observations.put(normalized, observation);
		if (fileKey != null) observationsByFileKey.put(fileKey, observation);
		return observation;
	}

	private static Object fileKey(Path file) throws IOException {
		try {
			return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
		} catch (UnsupportedOperationException e) {
			return null;
		}
	}

	private void assertPinned(Request request, PinnedGeneration pinned) throws IOException {
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cannot repair while an update transaction is active");
		String modpackId = request.activeTarget().manifest().modpackId();
		String contentToken = request.activeTarget().packTarget().contentToken();
		if (pinned.state() == null || !modpackId.equals(pinned.state().modpackId) || !contentToken.equals(pinned.state().contentToken)) throw new IOException("Repair target is no longer the active installed generation");
		if (pinned.activeTarget() == null) throw new IOException("Active client target is unavailable");
		if (!pinned.activeTarget().document().equals(request.activeTarget().document()) || !pinned.activeTarget().selection().intent().equals(request.activeTarget().selection().intent()))
			throw new IOException("Repair selection changed after preparation");
	}

	private static void requireSamePinnedIdentity(Prepared expected, Prepared actual) throws IOException {
		if (!expected.modpackId().equals(actual.modpackId()) || !expected.contentToken().equals(actual.contentToken()) || !expected.selectionDigest().equals(actual.selectionDigest()))
			throw new IOException("Active repair identity changed after preparation");
	}

	private static Path verifiedSource(Collection<Path> candidates, Path target, Content content, FileCache fileCache) throws IOException {
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
			logicalPath = LogicalPath.normalize(logicalPath);
			root = root.toAbsolutePath().normalize();
			path = path.toAbsolutePath().normalize();
			if (!path.startsWith(root)) throw new IllegalArgumentException("Expected repair path escaped its root");
		}
	}

	private record Observation(Path path, String hash, long size, boolean unsupported, Object fileKey) {
		private Observation(Path path, String hash, long size, boolean unsupported) {
			this(path, hash, size, unsupported, null);
		}
	}

	private record Analysis(Prepared prepared, Map<Path, Expected> expected, Map<Path, Observation> observations, Map<Content, List<Path>> sources) {}

	private record RepairCounts(long casObjects, long materializedFiles) {}
}
