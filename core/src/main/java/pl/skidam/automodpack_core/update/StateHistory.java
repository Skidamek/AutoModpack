package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.ClientStateJournal.Kind;
import pl.skidam.automodpack_core.update.ClientStateJournal.Snapshot;
import pl.skidam.automodpack_core.update.InstanceTree.Key;
import pl.skidam.automodpack_core.update.InstanceTree.TrackedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The user-facing instance timeline: snapshots of states this computer lived, whole-instance checkout, per-file
 * recovery, and forget-prefix. Diffs are computed from parent trees. Restore is offline.
 */
public final class StateHistory {
	private StateHistory() {}

	public enum Restorability {
		CURRENT, READY, NOT_KEPT
	}

	public enum FileGate {
		AVAILABLE, NOT_GAME_DIR, OWNED, PROTECTED
	}

	public record FileDiff(TrackedFile before, TrackedFile after) {
		public JournalEntry.Change.Kind kind() {
			if (before == null) return JournalEntry.Change.Kind.ADDED;
			if (after == null) return JournalEntry.Change.Kind.REMOVED;
			return JournalEntry.Change.Kind.CHANGED;
		}
	}

	public record SnapshotView(Snapshot snapshot, InstanceTree tree, List<FileDiff> diffs, Restorability restorability) {}

	public static List<Snapshot> entries(ClientStorage storage) throws IOException {
		return ClientStorageMutation.run(storage, () -> ClientStateJournal.open(storage).entries());
	}

	/** One pass over the timeline: trees, parent diffs, and restorability, with a single live observation. */
	public static List<SnapshotView> views(ClientStorage storage) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				ClientStateJournal journal = ClientStateJournal.open(storage);
				Map<String, InstanceTree> trees = new HashMap<>();
				Set<Key> extra = new TreeSet<>(Key.ORDER);
				for (Snapshot snapshot : journal.entries()) {
					InstanceTree tree = InstanceTree.read(storage, snapshot.treeSha1());
					trees.put(snapshot.treeSha1(), tree);
					extra.addAll(tree.keys());
				}
				InstanceTree live = InstanceTree.observe(storage, extra, cache);
				List<SnapshotView> views = new ArrayList<>();
				for (Snapshot snapshot : journal.entries()) {
					InstanceTree tree = trees.get(snapshot.treeSha1());
					InstanceTree parent = snapshot.parentSeq() == ClientStateJournal.NO_PARENT ? null : trees.get(journal.require(snapshot.parentSeq()).treeSha1());
					views.add(new SnapshotView(snapshot, tree, diff(parent, tree), restorabilityOf(storage, tree, live, cache)));
				}
				return List.copyOf(views);
			}
		});
	}

	static List<FileDiff> diff(InstanceTree parent, InstanceTree current) {
		Map<Key, TrackedFile> before = new HashMap<>();
		if (parent != null) for (TrackedFile file : parent.files()) before.put(file.key(), file);
		Map<Key, TrackedFile> after = new HashMap<>();
		for (TrackedFile file : current.files()) after.put(file.key(), file);
		Set<Key> keys = new TreeSet<>(Key.ORDER);
		keys.addAll(before.keySet());
		keys.addAll(after.keySet());
		List<FileDiff> diffs = new ArrayList<>();
		for (Key key : keys) {
			TrackedFile left = before.get(key);
			TrackedFile right = after.get(key);
			if (left != null && right != null && left.sha1().equals(right.sha1()) && left.size() == right.size()) continue;
			diffs.add(new FileDiff(left, right));
		}
		return diffs;
	}

	public static FileGate fileRestoreGate(ClientStorage storage, Root root, String path) throws IOException {
		if (root != Root.GAME_DIR) return FileGate.NOT_GAME_DIR;
		if (InstanceTree.isRunningModJar(storage.gamePath(path))) return FileGate.PROTECTED;
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		if (active == null) return FileGate.AVAILABLE;
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			var target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current()).orElse(null);
			if (target == null) return FileGate.AVAILABLE;
			String normalized = LogicalPath.normalize(path);
			if (target.flatTarget().list != null)
				for (var item : target.flatTarget().list)
					if (LogicalPath.normalize(item.file).equals(normalized)) return FileGate.OWNED;
			return FileGate.AVAILABLE;
		}
	}

	/** Snapshot live if it differs from head. Kind is LIVE when this is a dirty-before row. */
	public static void snapshotIfDirty(ClientStorage storage, Set<Key> extraPaths, Kind kind, String modpackId, String transactionId) throws IOException {
		snapshotIfDirty(storage, extraPaths, kind, modpackId, transactionId, null);
	}

	/**
	 * Same as {@link #snapshotIfDirty(ClientStorage, Set, Kind, String, String)} but skips a row when live already
	 * matches {@code toward} on every path that differs from head. Reconcile can move drifted files onto the pack
	 * version without a second timeline row; leftover player files that the plan will delete still snapshot.
	 */
	public static void snapshotIfDirty(ClientStorage storage, Set<Key> extraPaths, Kind kind, String modpackId, String transactionId, UpdatePlan toward) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				InstanceTree live = InstanceTree.observe(storage, extraPaths, cache);
				ClientStateJournal journal = ClientStateJournal.open(storage);
				if (!journal.entries().isEmpty()) {
					InstanceTree head = InstanceTree.read(storage, journal.head().treeSha1());
					if (live.sameAs(head) || toward != null && alreadyMovedTowardPlan(live, head, toward)) return null;
				}
				acquireTreeBlobs(storage, live, cache);
				live.write(storage);
				journal.append(live.sha1(), kind, modpackId, transactionId);
				return null;
			}
		});
	}

	public static void aroundMutation(ClientStorage storage, Set<Key> extraPaths, Kind kind, String modpackId, String transactionId, ClientStorageMutation.Operation<?> mutation) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			snapshotIfDirty(storage, extraPaths, Kind.LIVE, modpackId, transactionId);
			mutation.run();
			snapshotIfDirty(storage, extraPaths, kind, modpackId, transactionId);
			return null;
		});
	}

	public static Path checkout(ClientStorage storage, long seq) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				ClientStateJournal journal = ClientStateJournal.open(storage);
				Snapshot snapshot = journal.require(seq);
				InstanceTree target = InstanceTree.read(storage, snapshot.treeSha1());
				if (!blobsPresent(storage, target, cache)) throw new IOException("Instance snapshot " + seq + " is missing file data on this computer");
				if (!identityFeasible(storage, target.identity())) throw new IOException("Pack history has no generation " + target.identity().contentToken() + " for " + target.identity().activeModpackId());
				InstanceTree live = InstanceTree.observe(storage, target.keys(), cache);
				if (live.sameAs(target)) return storage.gameDirectory();
				applyTree(storage, live, target, cache);
				applyIdentity(storage, target.identity());
				detachInstalled(storage);
				InstanceTree after = InstanceTree.observe(storage, target.keys(), cache);
				if (!sameFiles(after, target)) throw new IOException("Instance restore did not reproduce snapshot " + seq);
				after.write(storage);
				journal.append(after.sha1(), Kind.RESTORE, target.identity().activeModpackId(), "restore-" + seq);
				return storage.gameDirectory();
			}
		});
	}

	public static void forgetOlderThan(ClientStorage storage, long seq) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			ClientStateJournal journal = ClientStateJournal.open(storage);
			List<Snapshot> remaining = journal.entries().stream().filter(entry -> entry.seq() >= seq).toList();
			if (remaining.isEmpty()) throw new IOException("Forgetting that prefix would leave no snapshots");
			journal.replaceAll(remaining);
			Set<String> kept = new HashSet<>();
			for (Snapshot entry : remaining) kept.add(entry.treeSha1());
			InstanceTree.deleteUnused(storage, kept);
			ClientObjectStore.collectUnreachableObjects(storage, Set.of());
			return null;
		});
	}

	public static Path restoreFile(ClientStorage storage, long seq, Root root, String path) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				Snapshot snapshot = ClientStateJournal.open(storage).require(seq);
				InstanceTree tree = InstanceTree.read(storage, snapshot.treeSha1());
				FileGate gate = fileRestoreGate(storage, root, path);
				if (gate == FileGate.NOT_GAME_DIR) throw new IOException("Only game-directory files can be restored to their original path");
				if (gate == FileGate.OWNED) throw new IOException("The active modpack still owns " + path);
				if (gate == FileGate.PROTECTED) throw new IOException("The running AutoModpack jar cannot be restored over");
				TrackedFile file = requireFile(tree, root, "", path);
				Path destination = storage.gamePath(path);
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && FileIntegrity.matchesNamed(destination, file.size(), file.sha1(), cache)) return destination;
				Set<Key> extra = Set.of(file.key());
				String transactionId = "file-restore-" + seq;
				snapshotIfDirty(storage, extra, Kind.LIVE, snapshot.modpackId(), transactionId);
				copyWithoutOverwrite(storage.gameDirectory(), storage.objectFile(file.sha1()), destination, file.size(), file.sha1(), cache);
				snapshotIfDirty(storage, extra, Kind.FILE_RESTORE, snapshot.modpackId(), transactionId);
				return destination;
			}
		});
	}

	public static Path saveFileCopy(ClientStorage storage, long seq, Root root, String overlayPackId, String path) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				InstanceTree tree = InstanceTree.read(storage, ClientStateJournal.open(storage).require(seq).treeSha1());
				TrackedFile file = requireFile(tree, root, overlayPackId, path);
				Path destination = RecoveredFiles.destination(storage, path, file.sha1());
				copyWithoutOverwrite(storage.gameDirectory(), storage.objectFile(file.sha1()), destination, file.size(), file.sha1(), cache);
				return destination;
			}
		});
	}

	/** Game-directory files from the parent of this pack's first install snapshot. Missing path means the pack created it. */
	public static Map<String, TrackedFile> priorGameDir(ClientStorage storage, String modpackId) throws IOException {
		ModpackId.requireValid(modpackId);
		return ClientStorageMutation.run(storage, () -> {
			ClientStateJournal journal = ClientStateJournal.open(storage);
			Snapshot install = null;
			for (Snapshot entry : journal.entries())
				if (entry.kind() == Kind.INSTALL && entry.modpackId().equals(modpackId)) {
					install = entry;
					break;
				}
			if (install == null || install.parentSeq() == ClientStateJournal.NO_PARENT) return Map.of();
			InstanceTree parent = InstanceTree.read(storage, journal.require(install.parentSeq()).treeSha1());
			Map<String, TrackedFile> files = new TreeMap<>();
			for (TrackedFile file : parent.files()) {
				if (file.root() != Root.GAME_DIR) continue;
				files.putIfAbsent(file.path(), file);
			}
			return Map.copyOf(files);
		});
	}

	/** Every location the plan touches, as tree keys: the plan is single-pack, so its overlay rows belong to the plan's pack. */
	static Set<Key> planPaths(UpdatePlan plan) {
		Set<Key> paths = new TreeSet<>(Key.ORDER);
		for (UpdatePlan.ProjectedFile projected : plan.projectedFinalState()) paths.add(plannedKey(plan, projected.root(), projected.relativePath()));
		for (UpdatePlan.Operation operation : plan.operations()) paths.add(plannedKey(plan, operation.root(), operation.relativePath()));
		for (UpdatePlan.Preservation preservation : plan.preservations()) paths.add(plannedKey(plan, preservation.root(), preservation.relativePath()));
		return paths;
	}

	private static Key plannedKey(UpdatePlan plan, Root root, String relativePath) {
		return new Key(root, root == Root.OVERLAY ? plan.modpackId() : "", relativePath);
	}

	static void copyWithoutOverwrite(Path constrainedRoot, Path source, Path destination, long size, String hash, FileCache cache) throws IOException {
		FileTrees.requireNoSymbolicLinkDescendants(constrainedRoot, destination, "restore destination");
		if (!FileIntegrity.matchesNamed(source, size, hash, cache)) throw new IOException("Instance tree object is missing or corrupt: " + hash);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !FileIntegrity.matchesNamed(destination, size, hash, cache))
				throw new IOException("Refusing to overwrite a different file at " + destination);
			return;
		}
		VerifiedFileTransfer.copyCreateOnly(source, destination, size, hash, cache);
		FileTrees.requireNoSymbolicLinkDescendants(constrainedRoot, destination, "restore destination");
		if (!FileIntegrity.matchesNamed(destination, size, hash, cache)) throw new IOException("Restored file failed verification: " + destination);
	}

	private static void applyTree(ClientStorage storage, InstanceTree live, InstanceTree target, FileCache cache) throws IOException {
		Set<Key> wanted = new HashSet<>();
		for (TrackedFile file : target.files()) {
			Path destination = storage.rootedPath(file.root(), file.overlayPackId(), file.path());
			if (InstanceTree.isRunningModJar(destination)) continue;
			wanted.add(file.key());
			Path object = storage.objectFile(file.sha1());
			if (FileIntegrity.matchesNamed(destination, file.size(), file.sha1(), cache)) continue;
			FileTrees.requireNoSymbolicLinkDescendants(storage.root(file.root(), file.overlayPackId().isEmpty() ? "_" : file.overlayPackId()), destination, "instance restore");
			if (file.root() == Root.PROJECTION) VerifiedFileTransfer.linkAtomic(object, destination, file.size(), file.sha1(), cache);
			else VerifiedFileTransfer.copyAtomic(object, destination, file.size(), file.sha1(), cache);
		}
		for (TrackedFile file : live.files()) {
			if (wanted.contains(file.key())) continue;
			Path destination = storage.rootedPath(file.root(), file.overlayPackId(), file.path());
			if (InstanceTree.isRunningModJar(destination)) continue;
			Files.deleteIfExists(destination);
			FileTrees.pruneEmptyAncestors(destination, storage.root(file.root(), file.overlayPackId().isEmpty() ? "_" : file.overlayPackId()));
		}
	}

	private static Restorability restorabilityOf(ClientStorage storage, InstanceTree target, InstanceTree live, FileCache cache) throws IOException {
		if (live.sameAs(target)) return Restorability.CURRENT;
		if (!blobsPresent(storage, target, cache) || !identityFeasible(storage, target.identity())) return Restorability.NOT_KEPT;
		return Restorability.READY;
	}

	private static boolean identityFeasible(ClientStorage storage, InstanceTree.LiveIdentity identity) throws IOException {
		if (identity.activeModpackId().isEmpty()) return true;
		for (JournalEntry entry : new JournalMirror(storage).entries(identity.activeModpackId()))
			if (entry.contentToken().equals(identity.contentToken())) return true;
		return false;
	}

	private static void applyIdentity(ClientStorage storage, InstanceTree.LiveIdentity identity) throws IOException {
		if (identity.activeModpackId().isEmpty()) {
			storage.clearActiveState();
			return;
		}
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		JournalEntry generation = null;
		for (JournalEntry entry : new JournalMirror(storage).entries(identity.activeModpackId()))
			if (entry.contentToken().equals(identity.contentToken())) {
				generation = entry;
				break;
			}
		if (generation == null) throw new IOException("Pack history has no generation " + identity.contentToken() + " for " + identity.activeModpackId());
		PackDocument document = generations.document(identity.activeModpackId(), generation);
		storage.writeActiveState(identity.activeModpackId(), identity.contentToken(), document.ownershipLedger().toFields());
		SelectionIntent target = identity.selection();
		if (target != null) {
			ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
			SelectionIntent current = selections.get(identity.activeModpackId()).orElse(null);
			selections.compareAndSet(identity.activeModpackId(), current, target);
		}
		Set<String> written = new HashSet<>();
		for (InstanceTree.Tombstone tombstone : identity.tombstones()) {
			storage.writeOverlayState(tombstone.modpackId(), new TreeSet<>(tombstone.deletedPaths()));
			written.add(tombstone.modpackId());
		}
		if (!written.contains(identity.activeModpackId())) storage.writeOverlayState(identity.activeModpackId(), Set.of());
	}

	private static void detachInstalled(ClientStorage storage) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		if (active == null) return;
		storage.setDetached(active.modpackId, true);
	}

	private static void acquireTreeBlobs(ClientStorage storage, InstanceTree tree, FileCache cache) throws IOException {
		for (TrackedFile file : tree.files()) {
			Path object = storage.objectFile(file.sha1());
			if (FileIntegrity.matchesNamed(object, file.size(), file.sha1(), cache)) continue;
			Path live = storage.rootedPath(file.root(), file.overlayPackId(), file.path());
			if (!FileIntegrity.matchesNamed(live, file.size(), file.sha1(), cache)) throw new IOException("Cannot pin instance tree file: " + file.path());
			VerifiedFileTransfer.copyAtomicImmutable(live, object, file.size(), file.sha1(), cache);
		}
	}

	private static boolean blobsPresent(ClientStorage storage, InstanceTree tree, FileCache cache) {
		for (TrackedFile file : tree.files())
			if (!FileIntegrity.matchesNamed(storage.objectFile(file.sha1()), file.size(), file.sha1(), cache)) return false;
		return true;
	}

	private static boolean alreadyMovedTowardPlan(InstanceTree live, InstanceTree head, UpdatePlan plan) {
		Map<Key, UpdatePlan.ProjectedFile> projected = new HashMap<>();
		for (UpdatePlan.ProjectedFile file : plan.projectedFinalState()) projected.put(plannedKey(plan, file.root(), file.relativePath()), file);
		Map<Key, TrackedFile> liveFiles = new HashMap<>();
		for (TrackedFile file : live.files()) liveFiles.put(file.key(), file);
		Map<Key, TrackedFile> headFiles = new HashMap<>();
		for (TrackedFile file : head.files()) headFiles.put(file.key(), file);
		if (!liveFiles.keySet().equals(headFiles.keySet())) return false;
		for (Key key : liveFiles.keySet()) {
			TrackedFile now = liveFiles.get(key);
			TrackedFile before = headFiles.get(key);
			if (now.sha1().equals(before.sha1()) && now.size() == before.size()) continue;
			UpdatePlan.ProjectedFile wanted = projected.get(key);
			if (wanted == null || !wanted.present() || !now.sha1().equalsIgnoreCase(wanted.expectedHash()) || now.size() != wanted.expectedSize()) return false;
		}
		return true;
	}

	/** A checkout must reproduce the snapshot's files exactly; the appended row records the state as it actually came back. */
	private static boolean sameFiles(InstanceTree left, InstanceTree right) {
		if (left.files().size() != right.files().size()) return false;
		for (int index = 0; index < left.files().size(); index++) {
			TrackedFile a = left.files().get(index);
			TrackedFile b = right.files().get(index);
			if (a.root() != b.root() || !a.overlayPackId().equals(b.overlayPackId()) || !a.path().equals(b.path()) || !a.sha1().equals(b.sha1()) || a.size() != b.size()) return false;
		}
		return true;
	}

	private static TrackedFile requireFile(InstanceTree tree, Root root, String overlayPackId, String path) throws IOException {
		TrackedFile file = tree.file(root, overlayPackId, path);
		if (file == null) throw new IOException("Instance tree does not track " + path);
		return file;
	}
}
