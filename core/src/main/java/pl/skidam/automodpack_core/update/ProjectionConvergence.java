package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
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
 * <li><b>V5 (durable before done).</b> Every directory whose entries changed is forced once before the run returns.</li>
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
		ProjectionState state = ProjectionState.repairing(storage, this::committedRows);
		Observation observed = observe();
		Work work = plan(observed, target);
		if (work.isEmpty()) return new Result(false, List.of());
		ProjectionState.converging(storage, transactionId, state.converging() ? List.of() : fallback(observed, hints));
		writes.marked();
		return execute(work);
	}

	/**
	 * P4: puts the fallback of a converging state back from CAS and settles it. A projection that held nothing
	 * before leaves no {@code active/} behind. Returns false while a held path keeps it converging.
	 */
	boolean restoreFallback() throws IOException {
		ProjectionState state = ProjectionState.repairing(storage, this::committedRows);
		if (!state.converging()) return true;
		Result result = execute(plan(observe(), state.fallback()));
		if (!result.converged()) {
			LOGGER.error("The fallback projection could not be restored: {} paths are held open, first {}", result.held().size(), result.held().get(0).path(), result.held().get(0).cause());
			return false;
		}
		if (state.fallback().isEmpty()) deleteEmptyActiveDirectory();
		ProjectionState.settled(storage, null);
		return true;
	}

	/** P6: the projection of the generation the active state names, the one a document that broke still guarantees. */
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

	/** Removals and prunes are the walked paths themselves, never re-resolved from a name, so a link is removed as itself. */
	private record Work(List<Path> removals, List<Path> prunes, List<ProjectionState.Row> installs) {
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
		List<Path> removals = new ArrayList<>(observed.others().values());
		for (var file : observed.files().entrySet()) if (!wanted.containsKey(file.getKey())) removals.add(file.getValue().path());
		List<Path> prunes = observed.directories().entrySet().stream().filter(directory -> !neededDirectories.contains(directory.getKey()))
				.sorted(Comparator.comparing((Map.Entry<String, Path> directory) -> directory.getKey().length()).reversed()).map(Map.Entry::getValue).toList();
		List<ProjectionState.Row> installs = new ArrayList<>();
		for (ProjectionState.Row row : wanted.values()) {
			Observed current = observed.files().get(row.path());
			if (current == null || !isObject(current, row)) installs.add(row);
		}
		return new Work(removals, prunes, installs);
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
		if (key != null && objectKey != null) return key.equals(objectKey);
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
	private List<ProjectionState.Row> fallback(Observation observed, Map<String, String> hints) throws IOException {
		List<ProjectionState.Row> rows = new ArrayList<>();
		for (Map.Entry<String, Observed> entry : observed.files().entrySet()) {
			Observed current = entry.getValue();
			if (!isLogicalName(entry.getKey())) {
				LOGGER.warn("Not keeping {} in the projection fallback: its name is no pack path", current.path());
				continue;
			}
			long size = current.attributes().size();
			String hint = hints.get(entry.getKey());
			if (HashUtils.isSha1(hint) && isObject(current, new ProjectionState.Row(entry.getKey(), hint, size))) {
				rows.add(new ProjectionState.Row(entry.getKey(), hint, size));
				continue;
			}
			String hash = HashUtils.normalizeSha1(cache.getOrComputeHash(current.path()));
			Path object = storage.objectFile(hash);
			if (!FileIntegrity.matchesNamed(object, size, hash, cache)) VerifiedFileTransfer.copyAtomicImmutable(current.path(), object, size, hash, cache);
			rows.add(new ProjectionState.Row(entry.getKey(), hash, size));
		}
		return rows;
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
		for (Path directory : work.prunes()) {
			if (heldBeneath(held, directory)) continue;
			writes.beforeWrite(directory);
			Files.deleteIfExists(directory);
			touched.add(directory.getParent());
		}
		for (ProjectionState.Row row : work.installs()) {
			Path target = resolve(active, row.path());
			if (heldBeneath(held, target) || heldAbove(held, target)) {
				held.add(new Held(target, held.get(0).cause()));
				continue;
			}
			try {
				writes.beforeWrite(target);
				install(target, row);
				touched.add(target.getParent());
				installed.add(Map.entry(target, row.sha1()));
			} catch (IOException e) {
				if (!UpdateCommit.isLockFailure(e)) throw e;
				held.add(new Held(target, e));
			}
		}
		// V3: the records go in one pass after the links, so the cache batches its writes instead of flushing between lookups.
		for (Map.Entry<Path, String> entry : installed) cache.overwriteCache(entry.getKey(), entry.getValue());
		for (Path directory : touched) FileTrees.forceDirectory(directory);
		return new Result(!touched.isEmpty(), List.copyOf(held));
	}

	private static boolean heldBeneath(List<Held> held, Path path) {
		for (Held entry : held) if (entry.path().startsWith(path) && !entry.path().equals(path)) return true;
		return false;
	}

	private static boolean heldAbove(List<Held> held, Path path) {
		for (Held entry : held) if (path.startsWith(entry.path()) && !entry.path().equals(path)) return true;
		return false;
	}

	/**
	 * A target path under {@code active/}. Lexical confinement suffices: the walk this run planned on followed no link,
	 * every link it found was removed before the first install, and an install beneath a link that could not be removed
	 * stays owed ({@link #heldAbove}); the mutation lock keeps every other writer of ours out.
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
	private void install(Path target, ProjectionState.Row row) throws IOException {
		Path object = storage.objectFile(row.sha1());
		Path parent = target.getParent();
		if (parent != null && ensuredDirectories.add(parent)) Files.createDirectories(parent);
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
