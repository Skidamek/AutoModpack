package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.platforms.PlatformSourceLookup;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Writes the URL-contract tree (head, journal, objects/&lt;sha1&gt;) as byte-for-byte copies of the hosted files, ready for
 * any static HTTPS host. Objects the platforms still serve themselves are pruned unless {@code includeAll} or
 * {@code exportHttpIncludeAll} keeps them as the host-side backstop; the returned receipt carries the counts. Nothing
 * already in the target directory is ever deleted, so stale objects from old generations may accumulate there; the
 * operator owns the directory. Orthogonal to hosting: works in every connection mode.
 */
public final class HttpExporter {
	private final GenerationStore generationStore;
	private final Path serverRoot;
	private final Supplier<ServerConfigJsons.ServerConfigFieldsV3> config;
	private final PlatformSourceLookup platformSourceLookup;
	private final OperationLocks locks;

	HttpExporter(GenerationStore generationStore, Path serverRoot, Supplier<ServerConfigJsons.ServerConfigFieldsV3> config, PlatformSourceLookup platformSourceLookup, OperationLocks locks) {
		this.generationStore = generationStore;
		this.serverRoot = serverRoot;
		this.config = config;
		this.platformSourceLookup = platformSourceLookup;
		this.locks = locks;
	}

	/** The manual export, off the caller's thread of control: it resolves platform sources off the lease and re-verifies the snapshot under it. */
	public Result export(Path targetDirectory, boolean includeAll) throws IOException {
		ServerConfigJsons.ServerConfigFieldsV3 serverConfig = config.get();
		String refused = refusalReason(serverConfig);
		if (refused != null) return new Result.Rejected(refused);
		Path target = resolveTarget(targetDirectory);
		boolean exportEverything = includeAll || exportEverythingConfigured(serverConfig);
		// The platform round-trip hashes every object and can spend seconds on the network, so the manual export
		// resolves it off the lease: holding the publication lease that long would reject concurrent publishes for
		// no correctness gain. Each attempt re-verifies under the lease that the resolved snapshot is still the live
		// generation - a publish that slipped in costs a re-resolve - and a second lost race falls back to the
		// fully-leased export, which cannot lose.
		for (int attempt = 0; attempt < 2; attempt++) {
			GenerationHosting hosting = generationStore.hosting();
			Map<String, Long> platformServed = resolvePlatformServed(hosting, exportEverything);
			try (OperationLocks.Lease operation = locks.acquire(false)) {
				if (operation == null) return new Result.Rejected("Another modpack operation is already in progress");
				if (generationStore.hosting().asMap().equals(hosting.asMap())) return exportCopied(target, exportEverything, hosting, platformServed);
			}
		}
		try (OperationLocks.Lease operation = locks.acquire(false)) {
			if (operation == null) return new Result.Rejected("Another modpack operation is already in progress");
			GenerationHosting hosting = generationStore.hosting();
			return exportCopied(target, exportEverything, hosting, resolvePlatformServed(hosting, exportEverything));
		}
	}

	/**
	 * The auto-export a publication runs inside its own lease, over the hosting snapshot the publication already
	 * built, where nothing can slip in mid-export: the resolve may simply run where it is, and the copy sees one
	 * consistent generation. The caller must hold an operation lease: the journal is appended in place during a
	 * publish, so a lease-free export can copy a torn one.
	 */
	Result exportLeased(Path targetDirectory, boolean includeAll, GenerationHosting hosting) throws IOException {
		ServerConfigJsons.ServerConfigFieldsV3 serverConfig = config.get();
		String refused = refusalReason(serverConfig);
		if (refused != null) return new Result.Rejected(refused);
		boolean exportEverything = includeAll || exportEverythingConfigured(serverConfig);
		return exportCopied(resolveTarget(targetDirectory), exportEverything, hosting, resolvePlatformServed(hosting, exportEverything));
	}

	/** One config snapshot decides both the refusal and the everything switch, so one export never mixes two readings. */
	private static String refusalReason(ServerConfigJsons.ServerConfigFieldsV3 serverConfig) {
		return serverConfig != null && serverConfig.validateSecrets ? "The pack validates download secrets, which a public mirror cannot enforce" : null;
	}

	private static boolean exportEverythingConfigured(ServerConfigJsons.ServerConfigFieldsV3 serverConfig) {
		return serverConfig != null && serverConfig.exportHttpIncludeAll;
	}

	private Path resolveTarget(Path targetDirectory) {
		return (targetDirectory.isAbsolute() ? targetDirectory : serverRoot.resolve(targetDirectory)).normalize();
	}

	/**
	 * The platform's served sizes for the snapshot's objects, asked with {@code exportEverything} as the off switch;
	 * a failed round-trip exports every object.
	 */
	private Map<String, Long> resolvePlatformServed(GenerationHosting hosting, boolean exportEverything) throws IOException {
		Map<String, Path> objects = new TreeMap<>();
		for (String key : hosting.asMap().keySet()) {
			if (isReservedDocument(key)) continue;
			if (!HashUtils.isSha1(key)) throw new IOException("Unexpected hosting key in the generation store: " + key);
			objects.put(HashUtils.normalizeSha1(key), hosting.get(key));
		}
		if (exportEverything || objects.isEmpty()) return Map.of();
		List<PlatformSourceLookup.Query> queries = new ArrayList<>();
		for (Map.Entry<String, Path> object : objects.entrySet())
			queries.add(new PlatformSourceLookup.Query(object.getKey(), Files.size(object.getValue()), object.getValue()));
		try {
			Map<String, Long> resolved = platformSourceLookup.platformSizes(queries);
			return resolved != null ? resolved : Map.of();
		} catch (RuntimeException e) {
			LOGGER.warn("Platform source resolution failed; exporting every object", e);
			return Map.of();
		}
	}

	/** Copies the snapshot's tree into the target: objects first, then journal, then the head - the commit pointer lands last. */
	private Result exportCopied(Path target, boolean exportEverything, GenerationHosting hosting, Map<String, Long> platformServed) throws IOException {
		int written = 0, omitted = 0, unresolvable = 0;
		for (Map.Entry<String, Path> entry : hosting.asMap().entrySet()) {
			String key = entry.getKey();
			// The reserved documents are written after every object, in Phase-A order below: a tree published by a
			// copy tool (aws s3 sync, rclone) serves its keys in write order, and the head is the commit pointer.
			if (isReservedDocument(key)) continue;
			Path destination;
			{
				String sha1 = HashUtils.normalizeSha1(key);
				Long served = platformServed.get(sha1);
				if (served != null && served.longValue() == Files.size(entry.getValue())) {
					omitted++;
					continue;
				}
				if (!exportEverything) unresolvable++;
				destination = target.resolve("objects").resolve(sha1);
			}
			Files.createDirectories(destination.getParent());
			// Objects are immutable and named by their hash, so an already-present file of any size is the same
			// bytes and the copy is skipped.
			if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) && Files.size(destination) == Files.size(entry.getValue())) {
				written++;
				continue;
			}
			copyAtomically(entry.getValue(), destination);
			written++;
		}
		// Documents land after every object, journal before head. They are the opposite of objects: fixed-shape
		// bodies whose bytes change while their size stays, the one file whose freshness the mirror exists to
		// serve, so they are re-exported unconditionally - and a bucket synced with aws s3 sync or rclone serves
		// its keys in write order, so a client can never see the new head beside the old journal - the head is
		// the commit pointer of the whole tree and lands last.
		for (String key : new String[]{GenerationHosting.JOURNAL_KEY, GenerationHosting.HEAD_DOCUMENT_KEY}) {
			Path source = hosting.get(key);
			if (source == null) continue;
			copyAtomically(source, target.resolve(key));
			written++;
		}
		return new Result.Exported(written, omitted, unresolvable);
	}

	/** Publishes one exported file through a same-directory temporary and an atomic move, so a static host never serves a half-written copy. */
	private static void copyAtomically(Path source, Path destination) throws IOException {
		Path temporary = Files.createTempFile(destination.getParent(), ".export-", null);
		try {
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/** head/journal: served beside the content-addressed objects, exported to the target root, never pruned. The waiting track is an object like any other. */
	private static boolean isReservedDocument(String key) {
		return key.equals(GenerationHosting.HEAD_DOCUMENT_KEY) || key.equals(GenerationHosting.JOURNAL_KEY);
	}

	public sealed interface Result permits Result.Exported, Result.Rejected {

		record Exported(int exportedCount, int omittedCount, int unresolvableCount) implements Result {
			public Exported {
				if (exportedCount < 0 || omittedCount < 0 || unresolvableCount < 0) throw new IllegalArgumentException("Negative export count");
			}

			public String receipt(String directory) {
				String breakdown = omittedCount == 0 && unresolvableCount == 0
						? ""
						: " (" + omittedCount + " objects omitted: served by Modrinth/CurseForge; " + unresolvableCount + " unresolvable → included)";
				return "Exported " + exportedCount + " files to " + directory + breakdown;
			}
		}

		/** The export produced no tree; the detail explains the refusal. */
		record Rejected(String detail) implements Result {
			public Rejected {
				detail = Objects.requireNonNull(detail);
			}
		}
	}
}
