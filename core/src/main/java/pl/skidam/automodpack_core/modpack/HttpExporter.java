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

import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
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
	private final Path serverRoot;
	private final PlatformSourceLookup platformSourceLookup;

	HttpExporter(Path serverRoot, PlatformSourceLookup platformSourceLookup) {
		this.serverRoot = serverRoot;
		this.platformSourceLookup = platformSourceLookup;
	}

	/**
	 * One export, over one hosting snapshot and one config snapshot, under the caller's operation lease. The lease is a
	 * parameter because the journal is appended in place during a publish: a lease-free export can copy a torn one. It
	 * is therefore this method's precondition to hold, not one it takes and hides, and there is nothing left for the
	 * caller to enforce on itself. A publication runs this from inside its own lease, over the hosting snapshot it just
	 * built; a manual export takes a lease and hands it over.
	 */
	public Result export(Path targetDirectory, boolean includeAll, ServerConfigJsons.ServerConfigFieldsV3 config, GenerationHosting hosting, OperationLocks.Lease lease) throws IOException {
		Objects.requireNonNull(lease, "an export runs under an operation lease");
		if (config != null && config.validateSecrets) return new Result.Refused(new Refusal("The pack validates download secrets, which a public mirror cannot enforce", null));
		boolean exportEverything = includeAll || (config != null && config.exportHttpIncludeAll);
		return exportCopied(resolveTarget(targetDirectory), exportEverything, hosting, resolvePlatformServed(hosting, exportEverything));
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
		Files.createDirectories(target);
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

	public sealed interface Result permits Result.Exported, Result.Refused {

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

		/** The export produced no tree; the refusal explains why. */
		record Refused(Refusal refusal) implements Result {
			public Refused {
				Objects.requireNonNull(refusal, "refusal");
			}
		}
	}
}
