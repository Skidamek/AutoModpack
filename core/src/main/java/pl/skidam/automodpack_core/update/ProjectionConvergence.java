package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Converges {@code active/} in place onto one complete projection, from CAS.
 *
 * <p>
 * The target is a whole tree, never an operation diff: every run observes what {@code active/} holds now and
 * derives the work from that observation alone, so a run is idempotent and can start over from any crash point,
 * including one inside a previous run, a run of a stacked plan, or a fallback restore (C1/C4).
 *
 * <p>
 * Invariants, each enforced here and tested in {@code ProjectionConvergenceTest}:
 * <ul>
 * <li><b>V1 (marked before mixed).</b> When the observation needs work, {@link ProjectionState#converging} is durable
 * before the first write, with the complete tree observed as the fallback ({@link #converge}).</li>
 * <li><b>V2 (removals first).</b> Every path the target does not name exactly - case included - is removed, and
 * every directory the target does not need is pruned, before anything is installed. So a case-only rename on a
 * case-insensitive filesystem never deletes the file it just installed, a file-to-directory or directory-to-file
 * transition never installs over the old shape, and no moment holds an old and a new copy of a mod at once.</li>
 * <li><b>V3 (identity, not content).</b> A path counts as converged when it is the target object's inode (one
 * stat of each); an installed path is the object's hard link, or a verified copy where links are not supported, and
 * its cache record is seeded from that identity. No run re-reads the pack.</li>
 * <li><b>V4 (held stays owed).</b> A path another process holds is reported and the rest of the work goes on; the
 * state stays converging, so no gate loads the mixed tree, and the next run finishes it.</li>
 * <li><b>V5 (durable before done).</b> Every directory whose entries changed, the parent of every directory the run
 * created included, is forced once before the run returns.</li>
 * <li><b>V6 (no unowned window).</b> An object the fallback acquires into CAS is pinned in this instance's ownership
 * receipt before it exists, and stays pinned by the converging state after, so no instance sharing the store
 * collects it ({@link #fallback}).</li>
 * <li><b>V7 (a restore keeps what CAS cannot give back).</b> A restore whose fallback CAS cannot complete falls back to
 * the committed generation; before it removes or replaces anything, the files the store holds no copy of are kept in a
 * repair backup ({@link #restoreFallback}).</li>
 * </ul>
 */
final class ProjectionConvergence {
	/** A path another process held: the owed part of the run. */
	record Held(Path path, IOException cause) {}

	/** What one run did: whether {@code active/} changed, and what stays owed. */
	record Result(boolean changed, List<Held> held) {
		boolean converged() {
			return held.isEmpty();
		}
	}

	/** Seam for the commit's probe: a crash before or a hold at every individual write. */
	interface Writes {
		Writes NONE = new Writes() {
		};

		default void beforeWrite(Path target) throws IOException {}

		default void marked() throws IOException {}

		/** V6: the hashes the fallback is about to acquire are pinned in this instance's receipt. */
		default void acquiring(Set<String> hashes) throws IOException {}

		/** V5: a directory's entries were forced. */
		default void forced(Path directory) {}
	}

	private final ClientStorage storage;
	private final FileCache cache;
	private final Writes writes;
	private final Map<String, Object> objectKeys = new HashMap<>();
	private final Set<Path> ensuredDirectories = new HashSet<>();

	ProjectionConvergence(ClientStorage storage, FileCache cache, Writes writes) {
		this.storage = Objects.requireNonNull(storage, "storage");
		this.cache = Objects.requireNonNull(cache, "cache");
		this.writes = Objects.requireNonNull(writes, "writes");
	}

	/** The complete projection a plan converges to: its present projection rows. */
	static List<ProjectionState.Row> targetRows(UpdatePlan plan) throws IOException {
		List<ProjectionState.Row> rows = new ArrayList<>();
		for (ProjectedFile projected : plan.projectedFinalState())
			if (projected != null && projected.root() == Root.PROJECTION && projected.present())
				rows.add(new ProjectionState.Row(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath()), projected.expectedHash(), projected.expectedSize()));
		return rows;
	}

	/**
	 * Converges {@code active/} onto {@code target}. {@code hints} name the objects the current files are expected to be
	 * (the committed generation, the plan's baselines), so the fallback answers by identity before any hash.
	 */
	Result converge(String transactionId, List<ProjectionState.Row> target, Map<String, String> hints) throws IOException {
		ProjectionState state = repairing();
		Observation observed = observe();
		Work work = plan(observed, target);
		if (work.isEmpty()) return new Result(false, List.of());
		// A repaired document fell back to the committed generation, so the tree it found is in no fallback: what the
		// store cannot give back is kept before the roll-forward replaces it (V7).
		if (state.fallbackSource() == ProjectionState.Fallback.COMMITTED) keepUnrecoverable(observed, work);
		Set<String> acquired = new HashSet<>();
		List<ProjectionState.Row> fallback = state.converging() ? List.of() : fallback(observed, hints, acquired);
		ProjectionState.converging(storage, transactionId, fallback, ProjectionState.digest(target));
		// V6: the state now pins what the fallback acquired, so the in-flight pins can retire into it.
		if (!acquired.isEmpty()) ClientObjectStore.publishOwnership(storage);
		writes.marked();
		return execute(work);
	}

	/**
	 * P6 of {@link ProjectionState}: unreadable contents are set aside and repaired without destroying anything. With no
	 * journal nothing can be converging (every convergence runs under one), and a detached pack is the player's, so the
	 * tree is vouched for as it stands. With a journal the projection may be mixed: it stays converging, falling back to
	 * the committed generation, while the journal rolls forward. A physical read failure propagates until it has
	 * persisted across a boot, then is repaired the same way (P8).
	 */
	private ProjectionState repairing() throws IOException {
		try {
			return ProjectionState.readForWriter(storage);
		} catch (ProjectionState.UnreadableException e) {
			ProjectionState.setAsideUnreadable(storage, e);
			if (activeDetached()) {
				LOGGER.error("The projection state of a detached pack was unreadable; keeping the projection exactly as it is");
				return ProjectionState.repairSettled(storage);
			}
			if (!Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) {
				LOGGER.warn("The projection state was unreadable and no update is pending; keeping the projection as it is");
				return ProjectionState.repairSettled(storage);
			}
			LOGGER.warn("The projection state was unreadable while an update is pending; the update rolls forward over the projection");
			return ProjectionState.repairConverging(storage);
		}
	}

	private boolean activeDetached() throws IOException {
		var active = storage.readActiveState();
		return active != null && active.detached;
	}

	/**
	 * P4: puts the fallback of a converging state back from CAS and settles it. A projection that held nothing
	 * before leaves no {@code active/} behind. Returns false while a held path keeps it converging.
	 */
	boolean restoreFallback() throws IOException {
		ProjectionState state = repairing();
		if (!state.converging()) return true;
		Observation observed = observe();
		boolean committed = state.fallbackSource() == ProjectionState.Fallback.COMMITTED;
		// A generation committed since the fallback was taken (an older version ran after a downgrade) is the complete one now.
		if (!committed && !Objects.equals(state.committedToken(), ProjectionState.committedToken(storage))) {
			LOGGER.warn("The committed generation changed while the projection converged; restoring that generation's projection");
			committed = true;
		}
		// F1: a fallback CAS can no longer give back in full is no complete tree; the committed generation is.
		if (!committed && !restorable(observed, state.fallback())) {
			LOGGER.warn("The projection fallback names objects the store no longer holds; restoring the committed generation's projection");
			committed = true;
		}
		if (committed && activeDetached()) {
			// A detached pack's tree is the player's, never rebuilt from a generation it detached from.
			LOGGER.error("A detached pack's projection cannot fall back to its recorded tree; keeping the projection exactly as it is");
			ProjectionState.restored(storage, null);
			return true;
		}
		List<ProjectionState.Row> rows = committed ? committedRows() : state.fallback();
		if (!restorable(observed, rows)) {
			LOGGER.error("Neither the projection fallback nor the committed generation is complete in the store; the projection stays refused until an update converges it");
			return false;
		}
		Work work = plan(observed, rows);
		keepUnrecoverable(observed, work);
		Result result = execute(work);
		if (!result.converged()) {
			LOGGER.error("The fallback projection could not be restored: {} paths are held open, first {}", result.held().size(), result.held().get(0).path(), result.held().get(0).cause());
			return false;
		}
		if (!committed && state.fallback().isEmpty()) deleteEmptyActiveDirectory();
		ProjectionState.restored(storage, rows);
		return true;
	}

	/** Whether every row is already in place, or its object is in the store to put it back. */
	private boolean restorable(Observation observed, List<ProjectionState.Row> rows) throws IOException {
		for (ProjectionState.Row row : rows) {
			Observed current = observed.files().get(row.path());
			if (current != null && isObject(current, row)) continue;
			if (!FileIntegrity.matchesNamed(storage.objectFile(row.sha1()), row.size(), row.sha1(), cache)) return false;
		}
		return true;
	}

	/**
	 * Before a restore destroys anything, every file it would remove or replace whose bytes the store cannot give back -
	 * one the player added or edited - is copied, durably, into a repair backup beside {@code active/}. Junk is not kept.
	 */
	private void keepUnrecoverable(Observation observed, Work work) throws IOException {
		Path active = storage.activeDirectory();
		List<Path> destroyed = new ArrayList<>(work.removals());
		for (ProjectionState.Row row : work.installs()) {
			Observed current = observed.files().get(row.path());
			if (current != null) destroyed.add(current.path());
		}
		Path backup = null;
		Set<Path> touched = new LinkedHashSet<>();
		for (Path path : destroyed) {
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
			String hash = HashUtils.normalizeSha1(cache.getOrComputeHash(path));
			if (FileIntegrity.matchesNamed(storage.objectFile(hash), Files.size(path), hash, cache)) continue;
			if (backup == null) backup = storage.clientDirectory().resolve(REPAIR_BACKUP_PREFIX + System.currentTimeMillis());
			Path kept = backup.resolve(active.relativize(path).toString());
			Files.createDirectories(kept.getParent());
			Files.copy(path, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			FileTrees.forceFile(kept);
			for (Path directory = kept.getParent(); directory != null && directory.startsWith(storage.clientDirectory()); directory = directory.getParent()) touched.add(directory);
			LOGGER.warn("Kept {} in {} before restoring the projection: the store holds no copy of it", path, backup);
		}
		for (Path directory : touched) FileTrees.forceDirectory(directory);
	}

	/** Where a restore keeps the files it would destroy that the store cannot give back. */
	static final String REPAIR_BACKUP_PREFIX = "projection-repair-backup-";

	/** The projection of the generation the active state names: the tree its COMMITTED marker vouched for. */
	private List<ProjectionState.Row> committedRows() throws IOException {
		List<ProjectionState.Row> rows = new ArrayList<>();
		var target = new ClientGenerationStore(storage).readActiveTarget();
		if (target.isPresent() && target.get().flatTarget().list != null)
			for (var item : target.get().flatTarget().list)
				if (item != null && item.file != null && HashUtils.isSha1(item.sha1) && item.size >= 0) rows.add(new ProjectionState.Row(item.file, item.sha1, item.size));
		return rows;
	}

	private void deleteEmptyActiveDirectory() throws IOException {
		Path active = storage.activeDirectory();
		if (!Files.isDirectory(active, LinkOption.NOFOLLOW_LINKS)) return;
		try (var entries = Files.list(active)) {
			if (entries.findAny().isPresent()) return;
		}
		Files.delete(active);
		FileTrees.forceDirectory(active.getParent());
	}

	/** One lstat walk of {@code active/}: exact names, nothing followed. */
	private record Observation(Map<String, Observed> files, Map<String, Path> others, Map<String, Path> directories) {}

	private record Observed(Path path, BasicFileAttributes attributes) {}

	private Observation observe() throws IOException {
		Path active = storage.activeDirectory();
		Map<String, Observed> files = new LinkedHashMap<>();
		Map<String, Path> others = new LinkedHashMap<>();
		Map<String, Path> directories = new LinkedHashMap<>();
		if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) return new Observation(files, others, directories);
		if (!Files.isDirectory(active, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Active client projection is not a directory: " + active);
		Files.walkFileTree(active, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
				if (!directory.equals(active)) directories.put(relative(active, directory), directory);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				String relative = relative(active, file);
				if (attributes.isRegularFile()) files.put(relative, new Observed(file, attributes));
				else others.put(relative, file);
				return FileVisitResult.CONTINUE;
			}
		});
		return new Observation(files, others, directories);
	}

	/** The name elements joined by {@code /}: a backslash inside a POSIX name stays part of that name. */
	private static String relative(Path root, Path path) {
		StringBuilder relative = new StringBuilder();
		for (Path element : root.relativize(path)) {
			if (!relative.isEmpty()) relative.append('/');
			relative.append(element);
		}
		return relative.toString();
	}

	/**
	 * Removals and prunes are the walked paths themselves, never re-resolved from a name, so a link is removed as itself.
	 * Junk ({@link ProjectionJunk}) the target does not ship goes best effort and alone never makes work.
	 */
	private record Work(List<Path> removals, List<Path> junk, List<Path> prunes, List<Path> junkPrunes, List<ProjectionState.Row> installs) {
		boolean isEmpty() {
			return removals.isEmpty() && prunes.isEmpty() && installs.isEmpty();
		}
	}

	private Work plan(Observation observed, List<ProjectionState.Row> target) throws IOException {
		Map<String, ProjectionState.Row> wanted = new LinkedHashMap<>();
		Set<String> neededDirectories = new HashSet<>();
		for (ProjectionState.Row row : target) {
			wanted.put(row.path(), row);
			for (String parent = parentOf(row.path()); parent != null; parent = parentOf(parent)) neededDirectories.add(parent);
		}
		List<Path> removals = new ArrayList<>();
		List<Path> junk = new ArrayList<>();
		// Directories with something beneath that is not junk: only those make pruning work.
		Set<String> substantive = new HashSet<>();
		for (var other : observed.others().entrySet()) {
			removals.add(other.getValue());
			markAncestors(other.getKey(), substantive);
		}
		for (var file : observed.files().entrySet()) {
			if (wanted.containsKey(file.getKey())) {
				markAncestors(file.getKey(), substantive);
				continue;
			}
			if (ProjectionJunk.isJunk(file.getKey())) {
				junk.add(file.getValue().path());
				continue;
			}
			removals.add(file.getValue().path());
			markAncestors(file.getKey(), substantive);
		}
		List<Path> prunes = new ArrayList<>();
		List<Path> junkPrunes = new ArrayList<>();
		observed.directories().entrySet().stream().filter(directory -> !neededDirectories.contains(directory.getKey()))
				.sorted(Comparator.comparing((Map.Entry<String, Path> directory) -> directory.getKey().length()).reversed())
				.forEach(directory -> (substantive.contains(directory.getKey()) ? prunes : junkPrunes).add(directory.getValue()));
		List<ProjectionState.Row> installs = new ArrayList<>();
		for (ProjectionState.Row row : wanted.values()) {
			Observed current = observed.files().get(row.path());
			if (current == null || !isObject(current, row)) installs.add(row);
		}
		// A junk-only directory standing where the target needs a file is in the way, so it is real work.
		for (ProjectionState.Row row : installs)
			if (observed.directories().containsKey(row.path()) && !substantive.contains(row.path())) {
				Path directory = observed.directories().get(row.path());
				junkPrunes.remove(directory);
				if (!prunes.contains(directory)) prunes.add(directory);
			}
		return new Work(removals, junk, prunes, junkPrunes, installs);
	}

	private static void markAncestors(String path, Set<String> directories) {
		for (String parent = parentOf(path); parent != null; parent = parentOf(parent)) directories.add(parent);
	}

	private static String parentOf(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? null : path.substring(0, slash);
	}

	/** V3: the observed file is the target object's inode; without file keys, the cache's identity answers. */
	private boolean isObject(Observed current, ProjectionState.Row row) {
		if (current.attributes().size() != row.size()) return false;
		Object key = current.attributes().fileKey();
		Object objectKey = objectKey(row.sha1());
		if (key != null && key.equals(objectKey)) return true;
		// A verified copy (no hard links on FAT, or a store on another volume) answers to the record seeded when it landed.
		return FileIntegrity.matchesObject(current.path(), storage.objectFile(row.sha1()), row.size(), row.sha1(), cache);
	}

	private Object objectKey(String sha1) {
		return objectKeys.computeIfAbsent(sha1, hash -> {
			try {
				BasicFileAttributes attributes = Files.readAttributes(storage.objectFile(hash), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				return attributes.isRegularFile() && attributes.fileKey() != null ? attributes.fileKey() : null;
			} catch (IOException e) {
				return null;
			}
		});
	}

	/**
	 * The complete tree {@code active/} holds before the first write (V1): each regular file answers by identity against
	 * its hinted object, then by its cache record. A file whose bytes are in no object is acquired into CAS first, so the
	 * restore can always put it back; a symbolic link or special file is not projection content and has no row.
	 */
	private List<ProjectionState.Row> fallback(Observation observed, Map<String, String> hints, Set<String> acquired) throws IOException {
		List<ProjectionState.Row> rows = new ArrayList<>();
		Map<Path, ProjectionState.Row> acquisitions = new LinkedHashMap<>();
		for (Map.Entry<String, Observed> entry : observed.files().entrySet()) {
			Observed current = entry.getValue();
			if (ProjectionJunk.isJunk(entry.getKey())) continue;
			if (!isLogicalName(entry.getKey())) {
				LOGGER.warn("Not keeping {} in the projection fallback: its name is no pack path", current.path());
				continue;
			}
			long size = current.attributes().size();
			String hint = hints.get(entry.getKey());
			ProjectionState.Row row;
			if (HashUtils.isSha1(hint) && isObject(current, new ProjectionState.Row(entry.getKey(), hint, size))) {
				row = new ProjectionState.Row(entry.getKey(), hint, size);
				// F1: a cache record can vouch for a file whose object is gone; the row must name bytes the store holds.
				if (!isObjectInode(current, row) && !FileIntegrity.matchesNamed(storage.objectFile(row.sha1()), size, row.sha1(), cache)) acquisitions.put(current.path(), row);
			} else {
				String hash = HashUtils.normalizeSha1(cache.getOrComputeHash(current.path()));
				row = new ProjectionState.Row(entry.getKey(), hash, size);
				if (!FileIntegrity.matchesNamed(storage.objectFile(hash), size, hash, cache)) acquisitions.put(current.path(), row);
			}
			rows.add(row);
		}
		if (acquisitions.isEmpty()) return rows;
		// V6: objects no generation owns yet are pinned in this instance's receipt before they exist, so a concurrent
		// instance's collection cannot take them in the window before the converging state names them.
		for (ProjectionState.Row row : acquisitions.values()) acquired.add(row.sha1());
		ClientObjectStore.publishOwnership(storage, acquired);
		writes.acquiring(Set.copyOf(acquired));
		for (Map.Entry<Path, ProjectionState.Row> acquisition : acquisitions.entrySet()) {
			ProjectionState.Row row = acquisition.getValue();
			Path object = storage.objectFile(row.sha1());
			if (!FileIntegrity.matchesNamed(object, row.size(), row.sha1(), cache)) VerifiedFileTransfer.copyAtomicImmutable(acquisition.getKey(), object, row.size(), row.sha1(), cache);
		}
		return rows;
	}

	private boolean isObjectInode(Observed current, ProjectionState.Row row) {
		Object key = current.attributes().fileKey();
		return key != null && key.equals(objectKey(row.sha1()));
	}

	private static boolean isLogicalName(String relative) {
		try {
			return LogicalPath.normalize(relative).equals(relative);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private Result execute(Work work) throws IOException {
		Path active = storage.activeDirectory();
		Set<Path> touched = new LinkedHashSet<>();
		List<Held> held = new ArrayList<>();
		List<Held> stuck = new ArrayList<>();
		List<Map.Entry<Path, String>> installed = new ArrayList<>();
		for (Path path : work.removals()) {
			try {
				writes.beforeWrite(path);
				removeProjectionFile(path);
				touched.add(path.getParent());
			} catch (IOException e) {
				if (!UpdateCommit.isLockFailure(e)) throw e;
				held.add(new Held(path, e));
			}
		}
		for (Path junk : work.junk()) {
			try {
				Files.deleteIfExists(junk);
				touched.add(junk.getParent());
			} catch (IOException e) {
				LOGGER.debug("Leaving {} in the projection: it is no pack content and cannot be removed now", junk, e);
			}
		}
		// Junk-only directories hold nothing substantive, so they can go before the deepest-first prunes.
		for (Path directory : work.junkPrunes()) {
			try {
				if (Files.deleteIfExists(directory)) touched.add(directory.getParent());
			} catch (IOException e) {
				LOGGER.debug("Leaving {} in the projection: only junk is left in it", directory, e);
			}
		}
		for (Path directory : work.prunes()) {
			if (heldBeneath(held, directory)) continue;
			writes.beforeWrite(directory);
			try {
				Files.deleteIfExists(directory);
				touched.add(directory.getParent());
			} catch (DirectoryNotEmptyException e) {
				// Only junk that could not go is left inside; it blocks a file the target needs at this path, nothing else.
				stuck.add(new Held(directory, e));
			}
		}
		for (ProjectionState.Row row : work.installs()) {
			Path target = resolve(active, row.path());
			Held blocker = blocker(held, stuck, target);
			if (blocker != null) {
				held.add(new Held(target, blocker.cause()));
				continue;
			}
			try {
				writes.beforeWrite(target);
				install(target, row, touched);
				touched.add(target.getParent());
				installed.add(Map.entry(target, row.sha1()));
			} catch (IOException e) {
				if (!UpdateCommit.isLockFailure(e)) throw e;
				held.add(new Held(target, e));
			}
		}
		// V3: the records go in one pass after the links, so the cache batches its writes instead of flushing between lookups.
		for (Map.Entry<Path, String> entry : installed) cache.overwriteCache(entry.getKey(), entry.getValue());
		for (Path directory : touched) {
			FileTrees.forceDirectory(directory);
			writes.forced(directory);
		}
		return new Result(!touched.isEmpty(), List.copyOf(held));
	}

	/** What keeps an install from landing: a held path beneath or above it, or a directory at it only junk keeps. */
	private static Held blocker(List<Held> held, List<Held> stuck, Path target) {
		for (Held entry : held)
			if (!entry.path().equals(target) && (entry.path().startsWith(target) || target.startsWith(entry.path()))) return entry;
		for (Held entry : stuck)
			if (entry.path().equals(target) || target.startsWith(entry.path())) return entry;
		return null;
	}

	private static boolean heldBeneath(List<Held> held, Path path) {
		for (Held entry : held) if (entry.path().startsWith(path) && !entry.path().equals(path)) return true;
		return false;
	}

	/**
	 * A target path under {@code active/}. Lexical confinement suffices: the walk this run planned on followed no link,
	 * every link it found was removed before the first install, and an install beneath a link that could not be removed
	 * stays owed ({@link #blocker}); the mutation lock keeps every other writer of ours out.
	 */
	private static Path resolve(Path active, String relative) throws IOException {
		Path resolved = active.resolve(LogicalPath.normalize(relative)).normalize();
		if (!resolved.startsWith(active) || resolved.equals(active)) throw new IOException("Projection path escapes the active directory: " + relative);
		return resolved;
	}

	/**
	 * Installs the object at {@code target} as its hard link or verified copy; the caller seeds the record (V3). A target the
	 * filesystem refuses to replace because it is read-only (FAT's DOS attribute) is unprotected and retried once, and the
	 * object it shared an inode with is protected again.
	 */
	private void install(Path target, ProjectionState.Row row, Set<Path> touched) throws IOException {
		Path object = storage.objectFile(row.sha1());
		Path parent = target.getParent();
		if (parent != null && ensuredDirectories.add(parent)) createDirectories(parent, touched);
		try {
			VerifiedFileTransfer.linkAtomic(object, target, row.size(), row.sha1(), cache);
		} catch (AccessDeniedException denied) {
			if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw denied;
			Path shared = unprotect(target);
			try {
				VerifiedFileTransfer.linkAtomic(object, target, row.size(), row.sha1(), cache);
			} finally {
				reprotect(shared);
			}
		}
	}

	/**
	 * V5 for directories: every directory this run creates is named in its parent's entries, so each created
	 * directory's parent is forced with the rest before the run returns, and a crash never loses a directory whose
	 * files were forced.
	 */
	private static void createDirectories(Path directory, Set<Path> touched) throws IOException {
		List<Path> missing = new ArrayList<>();
		for (Path current = directory; current != null && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS); current = current.getParent()) missing.add(current);
		Files.createDirectories(directory);
		for (Path created : missing) if (created.getParent() != null) touched.add(created.getParent());
	}

	/** Deletes a projection entry; a read-only refusal is retried once, and the object the file shared is protected again. */
	private void removeProjectionFile(Path path) throws IOException {
		try {
			Files.deleteIfExists(path);
		} catch (AccessDeniedException denied) {
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw denied;
			Path shared = unprotect(path);
			try {
				Files.deleteIfExists(path);
			} finally {
				reprotect(shared);
			}
		}
	}

	/** Protection is per inode: clearing it on a hard link clears it on the object, so the object sharing the inode is named for {@link #reprotect}. */
	private Path unprotect(Path path) throws IOException {
		String hash = cache.getHashOrNull(path);
		Path object = HashUtils.isSha1(hash) ? storage.objectFile(HashUtils.normalizeSha1(hash)) : null;
		Path shared = object != null && FileIntegrity.sameInode(path, object) ? object : null;
		ImmutableFiles.unprotect(path);
		return shared;
	}

	private static void reprotect(Path object) throws IOException {
		if (object != null && Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) ImmutableFiles.protect(object);
	}
}
