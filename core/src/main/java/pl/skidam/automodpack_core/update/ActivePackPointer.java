package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.ContentTree;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The active pack pointer: the one durable answer to what this client runs, and the detach flag that rides on it.
 *
 * <p>
 * It is unique state, so unusable content fails the boot in place and is never set aside: continuing as empty would
 * silently rejoin server enforcement. The {@link PackDocument} is the single write argument on purpose - pack,
 * generation, ledger and carried manifest describe one generation, and taking them apart once let a commit record a
 * pointer naming one thing while running another.
 */
final class ActivePackPointer {
	/** A one-byte sibling used only as a reading of the pointer filesystem's own clock; never durable state. */
	private static final String CLOCK_SUFFIX = ".clock";
	private final Path stateFile;
	private final Path clockFile;
	private volatile Cached cached;
	/** Last reading of the pointer filesystem's clock; a parsed state narrower than this cannot be racy against it. */
	private volatile long clockStamp = Long.MIN_VALUE;
	private record Cached(FileCache.FileFingerprint fingerprint, long validatedAtNanos, ClientStorageJsons.ClientGenerationStateFields state) {}

	ActivePackPointer(Path stateFile) {
		this.stateFile = Objects.requireNonNull(stateFile, "state file");
		this.clockFile = stateFile.resolveSibling("." + stateFile.getFileName() + CLOCK_SUFFIX);
	}

	/** Why selected and the pointer disagree, or null when the selection may follow the pointer (matching, or no pointer). */
	static String selectionMismatch(ClientConfigJsons.SelectedModpackFields selected, ClientStorageJsons.ClientGenerationStateFields state) {
		return selectionMismatch(selected == null ? null : selected.modpackId, state);
	}

	static String selectionMismatch(String followId, ClientStorageJsons.ClientGenerationStateFields state) {
		if (followId == null) followId = "";
		if (followId.isBlank()) return state == null ? null : "no modpack is selected";
		if (!ModpackId.isValid(followId)) return "the configured selected modpack ID is invalid: " + followId;
		if (state == null) return "the active state is missing";
		if (!followId.equals(state.modpackId)) return "the active state belongs to " + state.modpackId + ", but the selected modpack is " + followId;
		return null;
	}

	/**
	 * The pointer, or null when none was persisted yet. A commit reads it a dozen times, and every read used to
	 * parse and validate the whole carried manifest and ledger; one stat now answers whether the last parse still
	 * describes the file. Every publication replaces the file by atomic rename, so a changed inode always fails the
	 * fingerprint even on a filesystem with a coarse clock. Parsing and validation stay in the cache; the document
	 * leaves as a copy, so a caller that edits what it read cannot edit what the next reader gets.
	 */
	ClientStorageJsons.ClientGenerationStateFields read() throws IOException {
		return copy(state());
	}

	/** The parsed, validated instance, read-only to every caller; the document leaves {@link #read()} as a copy. */
	private ClientStorageJsons.ClientGenerationStateFields state() throws IOException {
		FileCache.FileFingerprint fingerprint;
		try {
			FileCache.StatSnapshot stats = FileCache.statSnapshot(stateFile);
			if (!stats.isTrackedRegularFile()) return readFromDisk();
			fingerprint = stats.fingerprint();
		} catch (NoSuchFileException e) {
			cached = null;
			return null;
		}
		Cached hit = cached;
		if (hit != null && hit.fingerprint().equals(fingerprint) && !isRacy(hit, fingerprint)) return hit.state();
		long validatedAtNanos = validatedAt(newestTimestampNanos(fingerprint));
		ClientStorageJsons.ClientGenerationStateFields state = readFromDisk();
		cached = state == null ? null : new Cached(fingerprint, validatedAtNanos, state);
		return state;
	}

	/**
	 * The newest stat clock the fingerprint carries. ctime, not only mtime, is the one that catches an in-place write
	 * whose author put the mtime back; an unknown clock is -1 and never wins, which keeps a fingerprint without a usable
	 * change time on the safe side of {@link #validatedAt(long)}.
	 */
	private static long newestTimestampNanos(FileCache.FileFingerprint fingerprint) {
		return Math.max(fingerprint.lastModifiedNanos(), fingerprint.changeTimeNanos());
	}

	/** Git's racily-clean rule: a state stamped at or after the clock reading of its proof could share every stat field with a rewrite in the same tick. */
	private static boolean isRacy(Cached cached, FileCache.FileFingerprint fingerprint) {
		return newestTimestampNanos(fingerprint) >= cached.validatedAtNanos();
	}

	/**
	 * A reading of the pointer filesystem's clock taken no later than now, from a one-byte sibling rewritten in place.
	 * Filesystems stamp ctime/mtime from a coarse clock (NTFS ticks, FAT's two seconds); a recent pointer is re-parsed
	 * once until the clock moves past its stamp, and only then is a fingerprint hit trusted. A filesystem that refuses
	 * the clock write fails closed: every hit is re-parsed rather than risking a stale generation.
	 */
	private long validatedAt(long newestNanos) {
		long stamp = clockStamp;
		if (newestNanos < stamp) return stamp;
		try {
			Files.createDirectories(stateFile.getParent());
			try (OutputStream out = Files.newOutputStream(clockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				out.write('0');
			}
			stamp = Files.getLastModifiedTime(clockFile, LinkOption.NOFOLLOW_LINKS).to(TimeUnit.NANOSECONDS);
		} catch (IOException | UnsupportedOperationException unavailableClock) {
			// An earlier reading stays valid. With none, MIN makes every hit racy, which re-parses instead of trusting.
			return stamp;
		}
		clockStamp = stamp;
		return stamp;
	}

	private ClientStorageJsons.ClientGenerationStateFields readFromDisk() throws IOException {
		return ConfigTools.readUnique(stateFile, ClientStorageJsons.ClientGenerationStateFields.class, "Client active state", ActivePackPointer::validated).orElse(null);
	}

	static ClientStorageJsons.ClientGenerationStateFields validated(ClientStorageJsons.ClientGenerationStateFields state) {
		if (!ModpackId.isValid(state.modpackId) || !HashUtils.isSha1(state.contentToken) || !"ACTIVE".equals(state.status))
			throw new IllegalArgumentException("Client active state identity is invalid");
		OwnershipLedger.fromFields(state.ownershipLedger);
		if (!state.modpackId.equals(state.ownershipLedger.modpackId)) throw new IllegalArgumentException("Client active state and its ledger belong to different modpacks");
		// A carried manifest has to be the generation this pointer names, or the pointer would describe something it does not run.
		if (state.manifest != null && !ContentTree.fromManifest(GroupManifestValidator.validate(state.manifest)).token().equals(state.contentToken))
			throw new IllegalArgumentException("Client active state carries a manifest that is not its own generation");
		return state;
	}

	/** Publishes the pointer for what the client now runs, keeping the pack's current detachment. */
	void write(PackDocument document, SelectionIntent applied) throws IOException {
		write(applied, base(document));
	}

	/** Publishes a generation with the detachment chosen by an explicit state transition. */
	void write(PackDocument document, SelectionIntent applied, boolean detached) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = base(document);
		state.detached = detached;
		write(applied, state);
	}

	/** Stages a pointer with no recorded selection: the pre-split shape, whose applied answer falls back to the store. */
	void write(PackDocument document) throws IOException {
		publish(base(document));
	}

	/** Clears the pointer: the client runs no active pack. */
	void clear() throws IOException {
		cached = null;
		Files.deleteIfExists(stateFile);
	}

	/** Whether the active pack runs detached: local sovereignty over server enforcement. Packs without the pointer never are. */
	boolean isDetached(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = read();
		return state != null && state.modpackId.equals(ModpackId.requireValid(modpackId)) && state.detached;
	}

	/** Sets the detachment flag; a no-op for packs without the pointer, since the flag lives there. */
	void setDetached(String modpackId, boolean detached) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		ClientStorageJsons.ClientGenerationStateFields state = read();
		if (state == null || !state.modpackId.equals(normalizedModpackId) || state.detached == detached) return;
		state.detached = detached;
		Files.createDirectories(stateFile.getParent());
		ConfigTools.writeAtomic(stateFile, state);
		cached = null;
	}

	private void write(SelectionIntent applied, ClientStorageJsons.ClientGenerationStateFields state) throws IOException {
		SelectionIntent selection = Objects.requireNonNull(applied, "applied selection");
		state.selectedGroups = List.copyOf(selection.requestedGroups());
		state.selectedCategories = List.copyOf(selection.requestedCategories());
		state.excludedGroups = List.copyOf(selection.excludedGroups());
		state.selectedPlatform = selection.platform() == null ? null : selection.platform().id();
		publish(state);
	}

	private ClientStorageJsons.ClientGenerationStateFields base(PackDocument document) throws IOException {
		Objects.requireNonNull(document, "installed document");
		ClientStorageJsons.ClientGenerationStateFields state = new ClientStorageJsons.ClientGenerationStateFields();
		state.modpackId = ModpackId.requireValid(document.manifest().modpackId());
		state.contentToken = HashUtils.requireDigest(document.contentToken(), "generation ID");
		state.ownershipLedger = document.ownershipLedger().toFields();
		state.manifest = document.manifest().toFields();
		state.manifestSha1 = document.policySha1();
		state.detached = currentDetachmentFor(state.modpackId);
		return state;
	}

	private void publish(ClientStorageJsons.ClientGenerationStateFields state) throws IOException {
		cached = null;
		Files.createDirectories(stateFile.getParent());
		ConfigTools.writeAtomic(stateFile, state);
		cached = null;
	}

	/** What one read hands out: a top-level copy of the cached document, so no reader can edit another's answer. */
	private static ClientStorageJsons.ClientGenerationStateFields copy(ClientStorageJsons.ClientGenerationStateFields state) {
		if (state == null) return null;
		ClientStorageJsons.ClientGenerationStateFields copy = new ClientStorageJsons.ClientGenerationStateFields();
		copy.modpackId = state.modpackId;
		copy.contentToken = state.contentToken;
		copy.status = state.status;
		copy.ownershipLedger = state.ownershipLedger;
		copy.manifest = state.manifest;
		copy.manifestSha1 = state.manifestSha1;
		copy.selectedGroups = state.selectedGroups;
		copy.selectedCategories = state.selectedCategories;
		copy.excludedGroups = state.excludedGroups;
		copy.selectedPlatform = state.selectedPlatform;
		copy.detached = state.detached;
		return copy;
	}

	/**
	 * The persistent detachment flag of the active pack: commits republish the same pack's sovereignty, so only an
	 * explicit attach or a cleared state may end it. An unreadable pointer fails the write instead of clearing the flag.
	 */
	private boolean currentDetachmentFor(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields current = read();
		return current != null && current.modpackId.equals(modpackId) && current.detached;
	}
}
