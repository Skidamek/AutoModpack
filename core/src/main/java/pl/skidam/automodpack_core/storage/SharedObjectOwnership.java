package pl.skidam.automodpack_core.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Durable, per-instance reachability receipts for the shared content-addressed store. */
public final class SharedObjectOwnership {
	private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

	private SharedObjectOwnership() {}

	@FunctionalInterface
	public interface ReferencedOperation<T> {
		T run(Set<String> globallyReferencedHashes) throws IOException;
	}

	/** Publishes the owner's complete current receipt before state starts depending on it. */
	public static void publish(DataRootResolver.Location location, String component, Set<String> referencedHashes) throws IOException {
		if (!location.shared()) return;
		withLock(location, () -> {
			writeOwner(location, component, canonical(referencedHashes));
			return null;
		});
	}

	/** Updates this owner and runs one collection decision against one locked global snapshot. */
	public static <T> T withGlobalReferences(DataRootResolver.Location location, String component, Set<String> referencedHashes, ReferencedOperation<T> operation) throws IOException {
		Set<String> canonical = canonical(referencedHashes);
		if (!location.shared()) return operation.run(canonical);
		return withLock(location, () -> {
			writeOwner(location, component, canonical);
			return operation.run(readAllOwners(location.layout()));
		});
	}

	private static void writeOwner(DataRootResolver.Location location, String component, Set<String> hashes) throws IOException {
		String canonicalComponent = requireComponent(component);
		Path owners = location.layout().objectOwnersDirectory();
		FileTrees.createManagedDirectory(owners, "shared object ownership directory");
		StorageJsons.ObjectOwnershipFields fields = new StorageJsons.ObjectOwnershipFields();
		fields.ownerId = location.ownerId();
		fields.component = canonicalComponent;
		fields.ownerPath = location.ownerPath().toString();
		fields.objectHashes = List.copyOf(hashes);
		ConfigTools.writeAtomic(owners.resolve(location.ownerId() + "." + canonicalComponent + ".json"), fields);
	}

	private static Set<String> readAllOwners(DataRootResolver.Layout layout) throws IOException {
		Path owners = layout.objectOwnersDirectory();
		if (!Files.exists(owners, LinkOption.NOFOLLOW_LINKS)) return Set.of();
		if (Files.isSymbolicLink(owners) || !Files.isDirectory(owners, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Shared object ownership root is not a directory: " + owners);
		TreeSet<String> result = new TreeSet<>();
		try (Stream<Path> paths = Files.list(owners)) {
			for (Path path : paths.sorted().toList()) {
				if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.getFileName().toString().endsWith(".json"))
					throw new IOException("Shared object ownership root contains an unsupported entry: " + path);
				StorageJsons.ObjectOwnershipFields fields = ConfigTools.read(path, StorageJsons.ObjectOwnershipFields.class)
						.orElseThrow(() -> new IOException("Shared object ownership receipt is empty: " + path));
				String ownerId = requireOwnerId(fields.ownerId);
				if (fields.ownerPath == null || fields.ownerPath.isBlank()) throw new IOException("Shared object ownership receipt has no owner path: " + path);
				String receiptId = path.getFileName().toString().substring(0, path.getFileName().toString().length() - ".json".length());
				String expectedReceiptId = ownerId + "." + requireComponent(fields.component);
				if (!expectedReceiptId.equals(receiptId) || fields.objectHashes == null) throw new IOException("Shared object ownership identity is invalid: " + path);
				Set<String> hashes = canonical(Set.copyOf(fields.objectHashes));
				if (!List.copyOf(hashes).equals(fields.objectHashes)) throw new IOException("Shared object ownership receipt is not canonical: " + path);
				result.addAll(hashes);
			}
		}
		return Collections.unmodifiableSet(result);
	}

	private static String requireOwnerId(String ownerId) throws IOException {
		try {
			return UUID.fromString(ownerId).toString();
		} catch (RuntimeException e) {
			throw new IOException("Invalid shared object ownership owner ID", e);
		}
	}

	private static Set<String> canonical(Set<String> hashes) throws IOException {
		TreeSet<String> result = new TreeSet<>();
		for (String hash : hashes) {
			if (!HashUtils.isSha1(hash)) throw new IOException("Invalid shared object ownership hash: " + hash);
			result.add(HashUtils.normalizeSha1(hash));
		}
		return Collections.unmodifiableSet(result);
	}

	private static String requireComponent(String component) throws IOException {
		if (component == null || component.isBlank() || !component.chars().allMatch(value -> value >= 'a' && value <= 'z'))
			throw new IOException("Invalid shared object ownership component: " + component);
		return component;
	}

	private static <T> T withLock(DataRootResolver.Location location, LockedOperation<T> operation) throws IOException {
		Path lockPath = location.layout().objectOwnershipLockFile();
		FileTrees.createManagedDirectory(location.root(), "shared data directory");
		ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
		jvmLock.lock();
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); FileLock ignored = channel.lock()) {
			return operation.run();
		} finally {
			jvmLock.unlock();
		}
	}

	@FunctionalInterface
	private interface LockedOperation<T> {
		T run() throws IOException;
	}
}
