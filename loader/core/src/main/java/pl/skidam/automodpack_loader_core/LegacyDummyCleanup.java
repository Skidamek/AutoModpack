package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransaction.LegacyDummyTarget;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.LegacyDummyFiles;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public final class LegacyDummyCleanup {
	private LegacyDummyCleanup() {}

	public static void migrate() throws IOException {
		ClientStorage storage = UpdateTransactionSupport.storage();
		Path registryPath = storage.dummyFilesFile();
		if (!Files.isRegularFile(registryPath, LinkOption.NOFOLLOW_LINKS)) return;
		Jsons.ClientDummyFiles registry;
		try {
			registry = ConfigTools.read(registryPath, Jsons.ClientDummyFiles.class).orElse(null);
		} catch (RuntimeException e) {
			LOGGER.warn("Preserving invalid legacy dummy registry {}; cleanup will be skipped", registryPath, e);
			return;
		}
		if (registry == null || registry.files == null || registry.files.isEmpty()) {
			Files.deleteIfExists(registryPath);
			return;
		}

		Set<String> remaining = new LinkedHashSet<>(registry.files);
		Map<Path, LegacyDummyTarget> lockedTargets = new LinkedHashMap<>();
		for (String entry : registry.files.stream().filter(Objects::nonNull).sorted().toList()) {
			Path target = resolveRegistryEntry(entry, storage.gameDirectory());
			if (target == null) {
				LOGGER.warn("Preserving unconstrained legacy dummy registry entry: {}", entry);
				continue;
			}
			LegacyDummyTarget constrained = constrain(target, storage);
			if (constrained == null) {
				LOGGER.warn("Preserving legacy dummy registry target outside supported roots: {}", target);
				continue;
			}
			try {
				UpdateTransactionExecutor.validateNoSymbolicLinkDescendants(constrainedRoot(constrained.root(), storage), target);
			} catch (IOException e) {
				LOGGER.warn("Preserving legacy dummy registry target with a symbolic-link component: {}", target);
				continue;
			}
			if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				remaining.remove(entry);
				continue;
			}
			if (!LegacyDummyFiles.matches(target)) {
				LOGGER.warn("Preserving legacy dummy registry target because it does not match AutoModpack's exact signature: {}", target);
				continue;
			}
			try {
				Files.delete(target);
				remaining.remove(entry);
				LOGGER.info("Deleted verified legacy dummy remnant {}", target);
			} catch (IOException e) {
				if (UpdateTransactionExecutor.isLockFailure(e)) {
					lockedTargets.put(target, constrained);
				} else {
					LOGGER.warn("Failed to delete verified legacy dummy remnant {}; preserving its registry entry", target, e);
				}
			}
		}
		persistRegistry(registryPath, registry, remaining);

		if (lockedTargets.isEmpty()) return;
		UpdateTransaction transaction = UpdateTransaction.createLegacyDummyCleanup(new ArrayList<>(lockedTargets.values()));
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor(transaction).commit(transaction);
		if (execution.success()) return;
		DetachedUpdateHelper.launch(transaction);
		LOGGER.warn("Legacy dummy cleanup transaction {} is waiting for locked files to be released", transaction.transactionId);
		new ReLauncher(UpdateType.AUTOMODPACK).restart(true);
		throw new UpdateDeferredException(transaction.transactionId, execution.blockedPath(), execution.message());
	}

	private static void persistRegistry(Path registryPath, Jsons.ClientDummyFiles registry, Set<String> remaining) throws IOException {
		if (remaining.equals(registry.files)) return;
		registry.files = new LinkedHashSet<>(remaining);
		ConfigTools.writeAtomic(registryPath, registry);
		if (remaining.isEmpty()) Files.deleteIfExists(registryPath);
	}

	private static Path resolveRegistryEntry(String entry, Path gameDirectory) {
		if (entry == null || entry.isBlank() || entry.indexOf('\0') >= 0) return null;
		try {
			Path parsed = Path.of(entry);
			Path resolved = (parsed.isAbsolute() ? parsed : gameDirectory.resolve(parsed)).toAbsolutePath().normalize();
			return resolved.startsWith(gameDirectory) ? resolved : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static LegacyDummyTarget constrain(Path target, ClientStorage storage) {
		Path gameDirectory = storage.gameDirectory();
		Path automodpackDirectory = storage.automodpackDirectory();
		if (!target.startsWith(gameDirectory) || target.equals(gameDirectory)) return null;
		if (target.startsWith(automodpackDirectory)) return target(Root.AUTOMODPACK_DIR, automodpackDirectory, target);
		return target(Root.GAME_DIR, gameDirectory, target);
	}

	private static Path constrainedRoot(Root root, ClientStorage storage) {
		Path gameDirectory = storage.gameDirectory();
		return switch (root) {
			case GAME_DIR -> gameDirectory;
			case AUTOMODPACK_DIR -> storage.automodpackDirectory();
			default -> throw new IllegalArgumentException("Unsupported legacy cleanup root: " + root);
		};
	}

	private static LegacyDummyTarget target(Root root, Path base, Path target) {
		String relative = base.relativize(target).toString().replace(File.separatorChar, '/');
		return relative.isBlank() ? null : new LegacyDummyTarget(root, relative);
	}
}
