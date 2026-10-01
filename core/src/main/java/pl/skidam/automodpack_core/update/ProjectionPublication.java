package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** The projection file walks of one commit: build the incoming tree, swap it over active, seed the cache records, verify either tree. */
final class ProjectionPublication {
	private final ClientStorage storage;
	private final FileCache fileCache;

	ProjectionPublication(ClientStorage storage, FileCache fileCache) {
		this.storage = storage;
		this.fileCache = fileCache;
	}

	void build(UpdateTransaction transaction) throws IOException {
		Path incoming = storage.incomingDirectory();
		FileTrees.delete(incoming);
		Files.createDirectories(incoming);
		int expected = 0;
		for (ProjectedFile projected : transaction.plan().projectedFinalState()) {
			if (projected.root() != Root.PROJECTION || !projected.present()) continue;
			expected++;
			Path source = storage.objectFile(projected.expectedHash());
			Path target = incoming.resolve(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath())).normalize();
			if (!target.startsWith(incoming)) throw new IOException("Projection path escapes incoming directory");
			VerifiedFileTransfer.linkAtomic(source, target, projected.expectedSize(), projected.expectedHash(), fileCache);
		}
		assertStructure(incoming, expected);
	}

	/**
	 * The freshly built tree's structural gate: every projected row was linked moments ago with its own size check
	 * under the mutation lock, and the post-swap seed gate re-proves every file's object identity on the final path.
	 * What this walk adds is shape: exactly the expected regular files, nothing else, and no symbolic links anywhere.
	 */
	private void assertStructure(Path incoming, int expected) throws IOException {
		int regular = 0;
		try (var paths = Files.walk(incoming)) {
			for (Path path : paths.toList()) {
				if (path.equals(incoming) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
				if (Files.isSymbolicLink(path)) throw new IOException("Incoming projection contains a symbolic link: " + path);
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Incoming projection holds a non-regular entry: " + path);
				regular++;
			}
		}
		if (regular != expected) throw new IOException("Incoming projection holds " + regular + " files, expected " + expected);
	}

	/**
	 * Swaps the freshly built incoming projection over active. Only the commit pipeline calls this: active was just
	 * verified not to match and nothing writes it before the move, and incoming was just loud-verified by
	 * {@link #build}, so re-walking either tree here would only repeat a verdict already handed down. Recovery
	 * re-enters through the pipeline's publication decision.
	 */
	void swap(UpdateTransaction transaction) throws IOException {
		Path active = storage.activeDirectory();
		Path incoming = storage.incomingDirectory();
		Path backup = storage.backupDirectory();
		if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
			FileTrees.delete(active);
		} else if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
			FileTrees.moveRecoverableDirectory(active, backup);
		}
		FileTrees.moveRecoverableDirectory(incoming, active);
		seedRecords(transaction);
	}

	/**
	 * The post-swap gate: every projected file answers its pack-object identity on the final path - two stats, no content read -
	 * and that answer seeds the cache records the next boot's worktree observation lives on. Without it, publication would
	 * re-hash the whole pack once per apply to rebuild the records the link-and-rename dance invalidates.
	 */
	private void seedRecords(UpdateTransaction transaction) throws IOException {
		Path active = storage.activeDirectory();
		if (!Files.isDirectory(active, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Active client projection is not a directory: " + active);
		for (ProjectedFile projected : transaction.plan().projectedFinalState()) {
			if (projected == null || projected.root() != Root.PROJECTION || !projected.present()) continue;
			Path file = active.resolve(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath())).normalize();
			if (!file.startsWith(active)) throw new IOException("Projection path escapes active directory");
			if (!FileIntegrity.matchesObject(file, storage.objectFile(projected.expectedHash()), projected.expectedSize(), projected.expectedHash(), fileCache))
				throw new IOException("Client projection file verification failed: " + file);
			fileCache.overwriteCache(file, projected.expectedHash());
		}
	}

	void verify(Path projection, List<ProjectedFile> finalState) throws IOException {
		Map<String, ProjectedFile> expected = new HashMap<>();
		for (ProjectedFile projected : finalState) if (projected.root() == Root.PROJECTION && projected.present()) expected.put(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath()), projected);
		if (!Files.isDirectory(projection, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Active client projection is not a directory: " + projection);
		try (var paths = Files.walk(projection)) {
			for (Path path : paths.filter(candidate -> !candidate.equals(projection)).toList()) {
				if (Files.isSymbolicLink(path)) throw new IOException("Client projection contains a symbolic link: " + path);
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				String relative = UpdateTransactionValidator.normalizeOperationPath(projection.relativize(path).toString());
				ProjectedFile expectedFile = expected.remove(relative);
				if (expectedFile == null || !FileIntegrity.matchesObject(path, storage.objectFile(expectedFile.expectedHash()), expectedFile.expectedSize(), expectedFile.expectedHash(), fileCache))
					throw new IOException("Client projection file verification failed: " + path);
			}
		}
		if (!expected.isEmpty()) throw new IOException("Client projection is missing files: " + expected.keySet());
	}

	boolean verifyQuietly(Path projection, List<ProjectedFile> finalState) {
		try {
			verify(projection, finalState);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
