package pl.skidam.automodpack_core.utils.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.RecoveryArchive;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Collects the object hashes still referenced by client state before shared-CAS maintenance. */
public final class ClientObjectStore {
	private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

	/** Returns every object still named by client state before shared-CAS collection runs. */
	public static Set<String> referencedHashes(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		Set<String> retained = new HashSet<>();
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		for (String generationId : generations.generationIds()) {
			GenerationRecord record = generations.read(generationId).orElseThrow(() -> new IOException("Client generation record is missing: " + generationId));
			for (var group : record.manifest().groups().values()) for (var file : group.files().values()) addHash(retained, file.sha1());
			for (var entry : record.ownershipLedger().entries().values()) for (var content : entry.historicalHashes()) addHash(retained, content.sha1());
		}
		collectBaselines(storage, retained);
		collectOverlays(storage, retained);
		collectRecovery(storage, retained);
		collectTransaction(storage, retained);
		return Set.copyOf(retained);
	}

	private static void collectBaselines(ClientStorage storage, Set<String> retained) throws IOException {
		if (!Files.exists(storage.baselinesDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		try (Stream<Path> modpacks = Files.list(storage.baselinesDirectory())) {
			for (Path modpack : modpacks.toList()) {
				if (Files.isSymbolicLink(modpack)) throw new IOException("Client baseline directory is a symbolic link: " + modpack);
				if (!Files.isDirectory(modpack, LinkOption.NOFOLLOW_LINKS)) continue;
				Path baseline = modpack.resolve("baseline.json");
				if (!Files.exists(baseline, LinkOption.NOFOLLOW_LINKS)) continue;
				if (Files.isSymbolicLink(baseline) || !Files.isRegularFile(baseline, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client baseline is not a regular file: " + baseline);
				Jsons.ClientBaselineFields fields = ConfigTools.read(baseline, Jsons.ClientBaselineFields.class)
						.orElseThrow(() -> new IOException("Client baseline is empty: " + baseline));
				if (fields.entries == null) continue;
				for (var entry : fields.entries) if (entry != null && !entry.absent && entry.objectHash != null) addHash(retained, entry.objectHash);
			}
		}
	}

	private static void collectOverlays(ClientStorage storage, Set<String> retained) throws IOException {
		if (!Files.exists(storage.overlaysDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		try (Stream<Path> modpacks = Files.list(storage.overlaysDirectory())) {
			for (Path modpack : modpacks.toList()) {
				if (Files.isSymbolicLink(modpack)) throw new IOException("Client overlay path is a symbolic link: " + modpack);
				if (!Files.isDirectory(modpack, LinkOption.NOFOLLOW_LINKS)) continue;
				try (Stream<Path> files = Files.walk(modpack)) {
					for (Path file : files.filter(path -> !path.equals(modpack)).toList()) {
						if (Files.isSymbolicLink(file)) throw new IOException("Client overlay contains a symbolic link: " + file);
						if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
							String hash = HashUtils.getHash(file);
							if (hash == null) throw new IOException("Cannot hash client overlay file: " + file);
							addHash(retained, hash);
						}
					}
				}
			}
		}
	}

	private static void collectRecovery(ClientStorage storage, Set<String> retained) throws IOException {
		if (!Files.exists(storage.recoveryDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		try (Stream<Path> modpacks = Files.list(storage.recoveryDirectory())) {
			for (Path modpack : modpacks.toList()) {
				if (Files.isSymbolicLink(modpack)) throw new IOException("Client recovery path is a symbolic link: " + modpack);
				if (!Files.isDirectory(modpack, LinkOption.NOFOLLOW_LINKS)) continue;
				RecoveryArchive.read(modpack);
			}
		}
	}

	private static void collectTransaction(ClientStorage storage, Set<String> retained) throws IOException {
		Path transactionPath = storage.transactionFile();
		if (!Files.exists(transactionPath, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isSymbolicLink(transactionPath) || !Files.isRegularFile(transactionPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client transaction is not a regular file");
		UpdateTransaction transaction = ConfigTools.read(transactionPath, UpdateTransaction.class)
				.orElseThrow(() -> new IOException("Client transaction is empty: " + transactionPath));
		if (transaction.operations != null) for (var operation : transaction.operations) if (operation != null) addHash(retained, operation.expectedObjectHash());
		if (transaction.projectedFinalState != null) for (var projected : transaction.projectedFinalState) if (projected != null && projected.present()) addHash(retained, projected.expectedHash());
		if (transaction.plannedBaselineCaptures != null) for (var capture : transaction.plannedBaselineCaptures) if (capture != null && !capture.absent()) addHash(retained, capture.expectedHash());
		if (transaction.plannedPreservations != null)
			for (var preservation : transaction.plannedPreservations)
				if (preservation != null) addHash(retained, preservation.expectedHash());
	}

	private static void addHash(Set<String> retained, String hash) throws IOException {
		if (hash == null || hash.isBlank()) return;
		try {
			retained.add(normalizeHash(hash));
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid client object reference: " + hash, e);
		}
	}

	public static String normalizeHash(String sha1) {
		if (sha1 == null || !SHA1.matcher(sha1).matches()) throw new IllegalArgumentException("Invalid client object SHA-1");
		return sha1.toLowerCase(Locale.ROOT);
	}

}
