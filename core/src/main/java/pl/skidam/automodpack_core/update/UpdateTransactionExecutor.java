package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.update.UpdatePlan.*;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

public final class UpdateTransactionExecutor {
	private static final int COPY_CONCURRENCY = 3;
	private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
	private static final Comparator<Operation> OPERATION_ORDER = Comparator.comparing((Operation operation) -> operation.operation().ordinal())
			.thenComparing(operation -> operation.root().ordinal()).thenComparing(Operation::relativePath);
	private final Context context;

	@FunctionalInterface
	public interface CommitAction {
		void run(UpdateTransaction transaction) throws IOException;
	}

	public record Context(
			Path gameDirectory,
			Path modpackDirectory,
			Path modsDirectory,
			Path storeDirectory,
			Path automodpackDirectory,
			Path transactionFile,
			Path transactionResultFile,
			Path clientConfigFile,
			Path deletionTimestampsFile,
			Path installedManifestFile,
			CommitAction beforeManifestAction) {}

	public record Execution(UpdateTransactionResult.Status status, UpdateTransaction transaction, String operation, Path blockedPath, String message) {
		public boolean success() {
			return status == UpdateTransactionResult.Status.SUCCESS;
		}
	}

	public UpdateTransactionExecutor(Context context) {
		this.context = Objects.requireNonNull(context);
	}

	public Execution commit(UpdatePlan plan, Jsons.ModpackContentFields targetManifest) throws IOException {
		return commit(UpdateTransaction.create(plan, targetManifest, context.modpackDirectory()));
	}

	public Execution commit(UpdateTransaction transaction) throws IOException {
		validate(transaction);
		if (Files.exists(context.transactionFile())) throw new IOException("An update transaction is already active for this game directory");
		ConfigTools.writeAtomic(context.transactionFile(), transaction);
		Files.deleteIfExists(context.transactionResultFile());
		return executePersisted(transaction);
	}

	public Execution recover(UpdateTransaction transaction) throws IOException {
		validate(transaction);
		return executePersisted(transaction);
	}

	public UpdateTransaction readPersisted() {
		return ConfigTools.read(context.transactionFile(), UpdateTransaction.class).orElse(null);
	}

	public void validate(UpdateTransaction transaction) throws IOException {
		try {
			validateUnchecked(transaction);
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Invalid update transaction", e);
		}
	}

	private void validateUnchecked(UpdateTransaction transaction) throws IOException {
		if (transaction == null) throw new IOException("Transaction is missing");
		if (transaction.schemaVersion != UpdateTransaction.CURRENT_SCHEMA_VERSION) throw new IOException("Unsupported transaction schema");
		try {
			UUID.fromString(transaction.transactionId);
		} catch (RuntimeException e) {
			throw new IOException("Invalid transaction UUID", e);
		}
		if (transaction.purpose == null) throw new IOException("Transaction purpose is missing");
		Path gameDirectory = context.gameDirectory().toAbsolutePath().normalize();
		Path automodpackDirectory = context.automodpackDirectory().toAbsolutePath().normalize();
		if (!context.modsDirectory().toAbsolutePath().normalize().equals(gameDirectory.resolve("mods"))
				|| !automodpackDirectory.equals(gameDirectory.resolve("automodpack"))
				|| !context.storeDirectory().toAbsolutePath().normalize().equals(automodpackDirectory.resolve("store"))
				|| !context.transactionFile().toAbsolutePath().normalize().equals(automodpackDirectory.resolve(".private/update-transaction.json"))
				|| !context.transactionResultFile().toAbsolutePath().normalize().equals(automodpackDirectory.resolve(".private/update-transaction-result.json")))
			throw new IOException("Transaction roots do not match the game-directory layout");
		if (transaction.operations == null || transaction.projectedFinalState == null || transaction.plannedDeletionTimestamps == null
				|| transaction.restartReasons == null)
			throw new IOException("Transaction fields are incomplete");

		Jsons.ModpackContentFields manifest = null;
		switch (transaction.purpose) {
			case MODPACK_UPDATE -> {
				ModpackId.requireValid(transaction.modpackId);
				validateModpackIdentity(transaction);
				try {
					manifest = transaction.targetManifest();
				} catch (RuntimeException e) {
					throw new IOException("Invalid embedded target manifest", e);
				}
				validateManifest(manifest, transaction.modpackId);
				if (transaction.plannedClientConfig == null) throw new IOException("Planned client config is missing");
				validatePlannedClientConfig(transaction);
				validateOrderedMetadata(transaction);
			}
			case SELF_UPDATE -> validateSelfUpdateMetadata(transaction);
		}

		Map<FileKey, ProjectedFile> finalState = validateFinalState(transaction.projectedFinalState, transaction.modpackId, transaction.purpose);

		List<Operation> sortedOperations = transaction.operations.stream().sorted(OPERATION_ORDER).toList();
		if (!transaction.operations.equals(sortedOperations)) throw new IOException("Transaction operations are not deterministically ordered");
		Set<FileKey> operationKeys = new HashSet<>();
		Set<Path> operationTargets = new HashSet<>();
		for (Operation operation : transaction.operations) {
			if (operation == null || operation.root() == null || operation.operation() == null) throw new IOException("Incomplete transaction operation");
			validatePurposeOperation(transaction.purpose, operation);
			String relative = normalizeOperationPath(operation.relativePath());
			FileKey key = new FileKey(operation.root(), relative);
			if (!operationKeys.add(key)) throw new IOException("Duplicate transaction operation target");
			Path physicalTarget = validateRootAndPath(operation.root(), relative, transaction.modpackId);
			if (!operationTargets.add(physicalTarget)) throw new IOException("Transaction operations alias the same physical target");
			ProjectedFile projected = finalState.get(key);
			if (projected == null) throw new IOException("Operation target is missing from projected final state");
			switch (operation.operation()) {
				case INSTALL_OBJECT -> validateInstall(operation, projected);
				case DELETE -> validateDelete(operation, projected);
				case CREATE_DIRECTORY, REMOVE_EMPTY_DIRECTORY -> validateDirectoryOperation(operation);
			}
		}
		if (transaction.purpose == UpdateTransaction.Purpose.SELF_UPDATE && !operationKeys.equals(finalState.keySet()))
			throw new IOException("Self-update operations and projected final state must match exactly");
		if (manifest != null) validateManifestProjection(manifest, finalState);
	}

	private void validateModpackIdentity(UpdateTransaction transaction) throws IOException {
		if (context.modpackDirectory() == null) throw new IOException("Modpack transaction context is incomplete");
		Path expectedModpackDirectory = context.modpackDirectory().toAbsolutePath().normalize();
		Path stableModpackDirectory = context.automodpackDirectory().resolve("modpacks").resolve(transaction.modpackId).toAbsolutePath().normalize();
		Path recordedModpackDirectory;
		try {
			recordedModpackDirectory = Path.of(transaction.canonicalModpackDirectory).toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			throw new IOException("Invalid canonical modpack directory", e);
		}
		if (!expectedModpackDirectory.equals(stableModpackDirectory) || !expectedModpackDirectory.equals(recordedModpackDirectory))
			throw new IOException("Transaction modpack directory is not stable modpack storage");
	}

	private static void validateSelfUpdateMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.modpackId != null || transaction.targetManifestJson != null || transaction.canonicalModpackDirectory != null
				|| transaction.plannedClientConfig != null || !transaction.plannedDeletionTimestamps.isEmpty() || !transaction.restartReasons.isEmpty())
			throw new IOException("Self-update transaction contains modpack metadata");
		long installs = transaction.operations.stream().filter(operation -> operation.operation() == OperationType.INSTALL_OBJECT).count();
		long deletions = transaction.operations.stream().filter(operation -> operation.operation() == OperationType.DELETE).count();
		if (installs != 1 || deletions > 1 || transaction.operations.size() != installs + deletions)
			throw new IOException("Self-update transaction must contain one install and at most one deletion");
	}

	private static void validatePurposeOperation(UpdateTransaction.Purpose purpose, Operation operation) throws IOException {
		if (purpose != UpdateTransaction.Purpose.SELF_UPDATE) return;
		if (operation.root() != Root.MODS_DIR || (operation.operation() != OperationType.INSTALL_OBJECT && operation.operation() != OperationType.DELETE))
			throw new IOException("Self-update operations are restricted to JAR replacement in the mods directory");
		Path relative = Path.of(operation.relativePath());
		if (relative.getNameCount() != 1 || !relative.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
			throw new IOException("Self-update target must be a direct JAR child of the mods directory");
	}

	private void validateManifest(Jsons.ModpackContentFields manifest, String modpackId) throws IOException {
		if (manifest == null || manifest.list == null || !modpackId.equals(manifest.modpackId) || !ModpackId.isValid(manifest.modpackId))
			throw new IOException("Embedded manifest identity is invalid");
		Set<String> normalizedPaths = new HashSet<>();
		for (var item : manifest.list) {
			if (item == null || item.type == null || item.type.isBlank()) throw new IOException("Manifest item is incomplete");
			String relative = normalizeManifestPath(item.file);
			if (!normalizedPaths.add(relative)) throw new IOException("Manifest contains duplicate normalized path: " + relative);
			parseNonnegativeSize(item.size);
			validateHash(item.sha1, "manifest SHA-1");
		}
		if (manifest.nonModpackFilesToDelete == null) throw new IOException("Manifest deletion list is missing");
		Set<String> deletionKeys = new HashSet<>();
		for (var deletion : manifest.nonModpackFilesToDelete) {
			if (deletion == null || deletion.timestamp == null || deletion.timestamp.isBlank()) throw new IOException("Manifest deletion metadata is incomplete");
			String relative = normalizeManifestPath(deletion.file);
			validateHash(deletion.sha1, "deletion SHA-1");
			if (!deletionKeys.add(deletion.timestamp + "\0" + relative)) throw new IOException("Manifest contains duplicate deletion metadata");
		}
	}

	private void validatePlannedClientConfig(UpdateTransaction transaction) throws IOException {
		Jsons.ClientConfigFieldsV3 config = transaction.plannedClientConfig;
		if (!transaction.modpackId.equals(config.selectedModpackId) || config.modpackConnections == null)
			throw new IOException("Planned client config does not select the transaction modpack");
		Jsons.ConnectionInfo connection = config.modpackConnections.get(transaction.modpackId);
		if (connection == null || !connection.isComplete()) throw new IOException("Planned client config has no complete selected connection");
	}

	private void validateOrderedMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.plannedDeletionTimestamps.stream().anyMatch(value -> value == null || value.isBlank())
				|| new LinkedHashSet<>(transaction.plannedDeletionTimestamps).size() != transaction.plannedDeletionTimestamps.size())
			throw new IOException("Invalid planned deletion timestamps");
		if (!transaction.plannedDeletionTimestamps.equals(transaction.plannedDeletionTimestamps.stream().sorted().toList()))
			throw new IOException("Planned deletion timestamps are not ordered");
		if (transaction.restartReasons.stream().anyMatch(Objects::isNull)
				|| new LinkedHashSet<>(transaction.restartReasons).size() != transaction.restartReasons.size())
			throw new IOException("Invalid restart reasons");
		if (!transaction.restartReasons.equals(transaction.restartReasons.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList()))
			throw new IOException("Restart reasons are not ordered");
	}

	private Map<FileKey, ProjectedFile> validateFinalState(List<ProjectedFile> entries, String modpackId, UpdateTransaction.Purpose purpose) throws IOException {
		Map<FileKey, ProjectedFile> finalState = new LinkedHashMap<>();
		Set<Path> physicalTargets = new HashSet<>();
		FileKey previous = null;
		for (ProjectedFile entry : entries) {
			if (entry == null || entry.root() == null) throw new IOException("Incomplete projected final-state entry");
			if (purpose == UpdateTransaction.Purpose.SELF_UPDATE) {
				Path selfUpdatePath = Path.of(entry.relativePath());
				if (entry.root() != Root.MODS_DIR || selfUpdatePath.getNameCount() != 1
						|| !selfUpdatePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
					throw new IOException("Self-update projected state is restricted to direct JAR children of the mods directory");
			}
			String relative = normalizeOperationPath(entry.relativePath());
			Path physicalTarget = validateRootAndPath(entry.root(), relative, modpackId);
			if (!physicalTargets.add(physicalTarget)) throw new IOException("Projected entries alias the same physical target");
			FileKey key = new FileKey(entry.root(), relative);
			if (previous != null && compareFileKeys(previous, key) >= 0) throw new IOException("Projected final state is not uniquely ordered");
			previous = key;
			if (entry.present()) {
				validateHash(entry.expectedHash(), "projected SHA-1");
				if (entry.expectedSize() < 0) throw new IOException("Invalid projected file size");
			} else if (entry.expectedHash() != null || entry.expectedSize() != -1) {
				throw new IOException("Projected absence has file metadata");
			}
			finalState.put(key, entry);
		}
		return finalState;
	}

	private void validateInstall(Operation operation, ProjectedFile projected) throws IOException {
		if (operation.root() == Root.STORE_DIR || operation.expectedExistingHash() != null) throw new IOException("Invalid install operation root/metadata");
		validateHash(operation.expectedObjectHash(), "install SHA-1");
		if (operation.expectedSize() < 0 || !projected.present() || operation.expectedSize() != projected.expectedSize()
				|| !operation.expectedObjectHash().equalsIgnoreCase(projected.expectedHash()))
			throw new IOException("Install operation does not match projected final state");
		Path source = context.storeDirectory().resolve(operation.expectedObjectHash());
		if (!SmartFileUtils.isValidFile(source, operation.expectedSize(), operation.expectedObjectHash()))
			throw new IOException("Required CAS object is missing or corrupt: " + operation.expectedObjectHash());
	}

	private void validateDelete(Operation operation, ProjectedFile projected) throws IOException {
		if (operation.root() == Root.STORE_DIR || operation.expectedObjectHash() != null || operation.expectedSize() != -1 || projected.present())
			throw new IOException("Invalid delete operation root/metadata");
		if (operation.expectedExistingHash() != null) validateHash(operation.expectedExistingHash(), "deletion expected SHA-1");
	}

	private static void validateDirectoryOperation(Operation operation) throws IOException {
		if (operation.root() == Root.STORE_DIR || operation.expectedObjectHash() != null || operation.expectedExistingHash() != null || operation.expectedSize() != -1)
			throw new IOException("Invalid directory operation metadata");
	}

	private void validateManifestProjection(Jsons.ModpackContentFields manifest, Map<FileKey, ProjectedFile> finalState) throws IOException {
		for (var item : manifest.list) {
			String relative = normalizeManifestPath(item.file);
			ProjectedFile projected = finalState.get(new FileKey(Root.MODPACK_DIR, relative));
			if (projected == null || !projected.present()) throw new IOException("Manifest file is absent from projected final state: " + relative);
			if (!item.editable && (!item.sha1.equalsIgnoreCase(projected.expectedHash()) || parseNonnegativeSize(item.size) != projected.expectedSize()))
				throw new IOException("Manifest file does not match projected final state: " + relative);
		}
	}

	private Execution executePersisted(UpdateTransaction transaction) throws IOException {
		Operation current = null;
		Path blockedPath = null;
		try {
			for (Operation operation : transaction.operations) {
				if (operation.operation() == OperationType.CREATE_DIRECTORY) {
					current = operation;
					Files.createDirectories(resolve(operation));
				}
			}
			List<SmartFileUtils.CopyRequest> copies = new ArrayList<>();
			for (Operation operation : transaction.operations) {
				if (operation.operation() != OperationType.INSTALL_OBJECT) continue;
				Path target = resolve(operation);
				Path source = context.storeDirectory().resolve(operation.expectedObjectHash());
				copies.add(new SmartFileUtils.CopyRequest(source, target, operation.expectedSize(), operation.expectedObjectHash()));
			}
			try {
				SmartFileUtils.copyVerifiedAtomicBatch(copies, COPY_CONCURRENCY);
			} catch (SmartFileUtils.CopyBatchException e) {
				blockedPath = e.target();
				for (Operation operation : transaction.operations) {
					if (operation.operation() == OperationType.INSTALL_OBJECT && resolve(operation).equals(blockedPath)) {
						current = operation;
						break;
					}
				}
				throw e;
			}
			for (Operation operation : transaction.operations) {
				if (operation.operation() != OperationType.INSTALL_OBJECT) continue;
				current = operation;
				if (!SmartFileUtils.isValidFile(resolve(operation), operation.expectedSize(), operation.expectedObjectHash()))
					throw new IOException("Installed file failed verification: " + resolve(operation));
			}
			for (Operation operation : transaction.operations) {
				if (operation.operation() != OperationType.DELETE) continue;
				current = operation;
				Path target = resolve(operation);
				if (Files.exists(target)) {
					if (operation.expectedExistingHash() == null || !operation.expectedExistingHash().equalsIgnoreCase(HashUtils.getHash(target)))
						throw new IOException("Deletion target changed after planning: " + target);
					Files.delete(target);
				}
			}
			for (Operation operation : transaction.operations) {
				if (operation.operation() != OperationType.REMOVE_EMPTY_DIRECTORY) continue;
				current = operation;
				Path target = resolve(operation);
				if (SmartFileUtils.isEmptyDirectory(target)) Files.deleteIfExists(target);
			}
			verifyFinalState(transaction.projectedFinalState);
			if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
				ConfigTools.writeAtomic(context.clientConfigFile(), transaction.plannedClientConfig);
				persistDeletionTimestamps(transaction.plannedDeletionTimestamps);
				if (context.beforeManifestAction() != null) context.beforeManifestAction().run(transaction);
				verifyFinalState(transaction.projectedFinalState);
				ModpackContentTools.write(context.installedManifestFile(), transaction.targetManifest());
			}
			Files.deleteIfExists(context.transactionFile());
			Files.deleteIfExists(context.transactionResultFile());
			return new Execution(UpdateTransactionResult.Status.SUCCESS, transaction, null, null, null);
		} catch (IOException e) {
			if (blockedPath == null && current != null) blockedPath = resolve(current);
			if (isLockFailure(e)) {
				UpdateTransactionResult result = new UpdateTransactionResult(transaction.transactionId, UpdateTransactionResult.Status.DEFERRED_LOCKED,
						current == null ? null : current.operation().name(), blockedPath == null ? null : blockedPath.toString(), e.getMessage());
				ConfigTools.writeAtomic(context.transactionResultFile(), result);
				return new Execution(UpdateTransactionResult.Status.DEFERRED_LOCKED, transaction, current == null ? null : current.operation().name(), blockedPath,
						e.getMessage());
			}
			throw new UpdateExecutionException(current == null ? null : current.operation().name(), blockedPath, e);
		}
	}

	private void verifyFinalState(List<ProjectedFile> finalState) throws IOException {
		for (ProjectedFile projected : finalState) {
			Path target = resolve(projected.root(), projected.relativePath());
			if (projected.present()) {
				if (!SmartFileUtils.isValidFile(target, projected.expectedSize(), projected.expectedHash()))
					throw new IOException("Projected final target verification failed: " + target);
			} else if (Files.exists(target)) {
				throw new IOException("Projected absent target exists: " + target);
			}
		}
	}

	private void persistDeletionTimestamps(Collection<String> additions) throws IOException {
		if (additions.isEmpty()) return;
		Jsons.ClientDeletedNonModpackFilesTimestamps timestamps = ConfigTools
				.read(context.deletionTimestampsFile(), Jsons.ClientDeletedNonModpackFilesTimestamps.class)
				.orElseGet(Jsons.ClientDeletedNonModpackFilesTimestamps::new);
		if (timestamps.timestamps == null) timestamps.timestamps = new LinkedHashSet<>();
		timestamps.timestamps.addAll(additions);
		ConfigTools.writeAtomic(context.deletionTimestampsFile(), timestamps);
	}

	private Path resolve(Operation operation) throws IOException {
		return resolve(operation.root(), operation.relativePath());
	}

	private Path resolve(Root operationRoot, String relativePath) throws IOException {
		Path root = root(operationRoot).toAbsolutePath().normalize();
		Path resolved = root.resolve(normalizeOperationPath(relativePath)).normalize();
		if (!resolved.startsWith(root)) throw new IOException("Operation escapes constrained root");
		validateNoSymbolicLinkDescendants(root, resolved);
		return resolved;
	}

	public static void validateNoSymbolicLinkDescendants(Path constrainedRoot, Path target) throws IOException {
		Path root = constrainedRoot.toAbsolutePath().normalize();
		Path resolved = target.toAbsolutePath().normalize();
		if (!resolved.startsWith(root)) throw new IOException("Target escapes constrained root");
		Path current = root;
		for (Path component : root.relativize(resolved)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) throw new IOException("Symbolic-link component is not allowed beneath transaction root: " + current);
		}
	}

	private Path root(Root root) throws IOException {
		Path path = switch (root) {
			case MODPACK_DIR -> context.modpackDirectory();
			case GAME_DIR -> context.gameDirectory();
			case MODS_DIR -> context.modsDirectory();
			case STORE_DIR -> context.storeDirectory();
			case AUTOMODPACK_DIR -> context.automodpackDirectory();
		};
		if (path == null) throw new IOException("Transaction context does not provide root " + root);
		return path;
	}

	private Path validateRootAndPath(Root root, String relativePath, String currentModpackId) throws IOException {

		if (root == Root.STORE_DIR) throw new IOException("Transactions may not mutate the content-addressed store");
		Path constrainedRoot = root(root).toAbsolutePath().normalize();
		Path resolved = constrainedRoot.resolve(relativePath).normalize();
		if (!resolved.startsWith(constrainedRoot)) throw new IOException("Transaction path escapes constrained root");
		validateNoSymbolicLinkDescendants(constrainedRoot, resolved);
		if (root == Root.GAME_DIR && (resolved.startsWith(context.modsDirectory().toAbsolutePath().normalize())
				|| resolved.startsWith(context.automodpackDirectory().toAbsolutePath().normalize())))
			throw new IOException("GAME_DIR operation must use the narrower constrained root");
		if (context.installedManifestFile() != null && resolved.equals(context.installedManifestFile().toAbsolutePath().normalize()))
			throw new IOException("Installed manifest may only be published by the executor");
		if (root == Root.AUTOMODPACK_DIR) validateModpackStoragePath(relativePath, currentModpackId);
		return resolved;
	}

	private static void validateModpackStoragePath(String relativePath, String currentModpackId) throws IOException {
		Path path = Path.of(relativePath);
		if (path.getNameCount() < 3 || !"modpacks".equals(path.getName(0).toString()) || !ModpackId.isValid(path.getName(1).toString()))
			throw new IOException("AUTOMODPACK_DIR operations must target stable modpack storage");
		if (currentModpackId.equals(path.getName(1).toString()))
			throw new IOException("Current modpack files must use MODPACK_DIR rather than AUTOMODPACK_DIR");
	}

	private static String normalizeManifestPath(String path) throws IOException {
		try {
			return UpdatePlanner.normalize(path);
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsafe manifest path", e);
		}
	}

	private static String normalizeOperationPath(String relativePath) throws IOException {
		if (relativePath == null || relativePath.startsWith("/") || relativePath.startsWith("\\")
				|| relativePath.matches("^[A-Za-z]:[\\\\/].*"))
			throw new IOException("Operation path must be relative");
		try {
			String normalized = UpdatePlanner.normalize(relativePath);
			if (!normalized.equals(relativePath.replace('\\', '/'))) throw new IOException("Path is not normalized: " + relativePath);
			return normalized;
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsafe operation path", e);
		}
	}

	private static long parseNonnegativeSize(String value) throws IOException {
		try {
			long size = Long.parseLong(value);
			if (size < 0) throw new NumberFormatException("negative");
			return size;
		} catch (RuntimeException e) {
			throw new IOException("Invalid nonnegative file size", e);
		}
	}

	private static void validateHash(String hash, String description) throws IOException {
		if (hash == null || !SHA1.matcher(hash).matches()) throw new IOException("Invalid " + description);
	}

	private static int compareFileKeys(FileKey first, FileKey second) {
		int root = Integer.compare(first.root().ordinal(), second.root().ordinal());
		return root != 0 ? root : first.relativePath().compareTo(second.relativePath());
	}

	private static boolean isLockFailure(IOException exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof FileSystemException fileSystemException) {
				String detail = String.join(" ", Objects.toString(fileSystemException.getReason(), ""), Objects.toString(fileSystemException.getMessage(), ""))
						.toLowerCase(Locale.ROOT);
				if (detail.contains("used by another process") || detail.contains("being used by another process") || detail.contains("sharing violation")) return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
