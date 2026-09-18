package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.utils.HashUtils.isCanonicalSha1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * One instance tree: live identity plus every tracked file. Documents live per-instance under
 * {@code state-history/trees/<sha1>}; file bytes stay in shared CAS.
 */
public final class InstanceTree {
	private static final Gson COMPACT = ConfigTools.strictEnums(new GsonBuilder().disableHtmlEscaping()).create();
	static final Comparator<TrackedFile> FILE_ORDER = Comparator.comparing((TrackedFile file) -> file.root().ordinal()).thenComparing(TrackedFile::overlayPackId).thenComparing(TrackedFile::path);
	private static final Comparator<Tombstone> TOMBSTONE_ORDER = Comparator.comparing(Tombstone::modpackId);

	public record TrackedFile(Root root, String overlayPackId, String path, String sha1, long size) {
		public TrackedFile {
			Objects.requireNonNull(root, "root");
			overlayPackId = overlayPackId == null || overlayPackId.isBlank() ? "" : ModpackId.requireValid(overlayPackId);
			if (root == Root.OVERLAY && overlayPackId.isEmpty()) throw new IllegalArgumentException("Overlay files need a pack id: " + path);
			if (root != Root.OVERLAY && !overlayPackId.isEmpty()) throw new IllegalArgumentException("Only overlay files carry a pack id: " + path);
			path = LogicalPath.requireCanonical(path);
			if (!isCanonicalSha1(sha1)) throw new IllegalArgumentException("Invalid tree file hash for " + path);
			if (size < 0) throw new IllegalArgumentException("Negative tree file size for " + path);
		}

		public Key key() {
			return new Key(root, overlayPackId, path);
		}
	}

	/** Where a tracked file lives: the root, the pack owning an overlay row, and the root-relative path. */
	public record Key(Root root, String overlayPackId, String path) {
		public static final Comparator<Key> ORDER = Comparator.comparing((Key key) -> key.root().ordinal()).thenComparing(Key::overlayPackId).thenComparing(Key::path);

		public Key {
			Objects.requireNonNull(root, "root");
			overlayPackId = overlayPackId == null || overlayPackId.isBlank() ? "" : ModpackId.requireValid(overlayPackId);
			if (root == Root.OVERLAY && overlayPackId.isEmpty()) throw new IllegalArgumentException("Overlay locations need a pack id: " + path);
			if (root != Root.OVERLAY && !overlayPackId.isEmpty()) throw new IllegalArgumentException("Only overlay locations carry a pack id: " + path);
			path = LogicalPath.requireCanonical(path);
		}
	}

	public record Tombstone(String modpackId, List<String> deletedPaths) {
		public Tombstone {
			modpackId = ModpackId.requireValid(modpackId);
			deletedPaths = List.copyOf(new TreeSet<>(deletedPaths));
		}
	}

	public record LiveIdentity(String activeModpackId, String contentToken, boolean detached, SelectionIntent selection, List<Tombstone> tombstones) {
		public LiveIdentity {
			activeModpackId = activeModpackId == null || activeModpackId.isBlank() ? "" : ModpackId.requireValid(activeModpackId);
			contentToken = contentToken == null || contentToken.isBlank() ? "" : HashUtils.normalizeSha1(contentToken);
			if (!activeModpackId.isEmpty() && contentToken.isEmpty()) throw new IllegalArgumentException("Active pack is missing its content token");
			tombstones = tombstones == null ? List.of() : tombstones.stream().sorted(TOMBSTONE_ORDER).toList();
		}

		static LiveIdentity empty() {
			return new LiveIdentity("", "", false, null, List.of());
		}
	}

	private final String sha1;
	private final LiveIdentity identity;
	private final List<TrackedFile> files;

	private InstanceTree(String sha1, LiveIdentity identity, List<TrackedFile> files) {
		this.sha1 = sha1;
		this.identity = identity;
		this.files = files;
	}

	public String sha1() {
		return sha1;
	}

	public LiveIdentity identity() {
		return identity;
	}

	public List<TrackedFile> files() {
		return files;
	}

	public Set<Key> keys() {
		Set<Key> keys = new TreeSet<>(Key.ORDER);
		for (TrackedFile file : files) keys.add(file.key());
		return keys;
	}

	public TrackedFile file(Root root, String overlayPackId, String path) {
		String pack = overlayPackId == null ? "" : overlayPackId;
		return files.stream().filter(file -> file.root() == root && file.overlayPackId().equals(pack) && file.path().equals(path)).findFirst().orElse(null);
	}

	public static InstanceTree of(LiveIdentity identity, List<TrackedFile> files) {
		List<TrackedFile> sorted = new ArrayList<>(files);
		sorted.sort(FILE_ORDER);
		for (int index = 1; index < sorted.size(); index++)
			if (FILE_ORDER.compare(sorted.get(index - 1), sorted.get(index)) == 0) throw new IllegalArgumentException("The instance tree repeats a path");
		LiveIdentity canonical = Objects.requireNonNull(identity, "identity");
		String sha1 = HashUtils.sha1(COMPACT.toJson(toFields(canonical, List.copyOf(sorted))).getBytes(StandardCharsets.UTF_8));
		return new InstanceTree(sha1, canonical, List.copyOf(sorted));
	}

	public static InstanceTree read(ClientStorage storage, String sha1) throws IOException {
		String normalized = HashUtils.normalizeSha1(sha1);
		Path file = storage.stateHistoryTreeFile(normalized);
		ClientStorageJsons.InstanceTreeFields fields = ConfigTools.readUnique(file, ClientStorageJsons.InstanceTreeFields.class, "Instance tree", parsed -> parsed)
				.orElseThrow(() -> new IOException("Instance tree is missing: " + normalized));
		InstanceTree tree = fromFields(fields);
		if (!tree.sha1.equals(normalized)) throw new IOException("Instance tree hash does not match its file: " + normalized);
		return tree;
	}

	public void write(ClientStorage storage) throws IOException {
		Path file = storage.stateHistoryTreeFile(sha1);
		if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
		Files.createDirectories(file.getParent());
		ConfigTools.writeAtomic(file, toFields(identity, files));
	}

	static void deleteUnused(ClientStorage storage, Set<String> keptHashes) throws IOException {
		Path directory = storage.stateHistoryTreesDirectory();
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
		FileTrees.requireDirectory(directory, "instance trees");
		try (var paths = Files.list(directory)) {
			for (Path path : paths.toList()) {
				FileTrees.requireNoSymbolicLink(path, "instance tree");
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				String name = path.getFileName().toString();
				if (!keptHashes.contains(name)) Files.deleteIfExists(path);
			}
		}
	}

	static InstanceTree observe(ClientStorage storage, Set<Key> extraPaths, FileCache cache) throws IOException {
		Set<Key> paths = new TreeSet<>(Key.ORDER);
		paths.addAll(extraPaths);
		ClientStateJournal journal = ClientStateJournal.open(storage);
		if (!journal.entries().isEmpty()) paths.addAll(read(storage, journal.head().treeSha1()).keys());
		List<TrackedFile> files = new ArrayList<>();
		for (Key key : paths) {
			Path disk = storage.rootedPath(key.root(), key.overlayPackId(), key.path());
			if (!Files.isRegularFile(disk, LinkOption.NOFOLLOW_LINKS) || isRunningModJar(disk)) continue;
			long size = Files.size(disk);
			String hash = FileIntegrity.observedHash(disk, size, null, cache);
			files.add(new TrackedFile(key.root(), key.overlayPackId(), key.path(), HashUtils.normalizeSha1(hash), size));
		}
		return of(observeIdentity(storage), files);
	}

	/** The running AutoModpack jar is the updater itself: it is never tracked, never restored over, and never deleted by a checkout. */
	static boolean isRunningModJar(Path absoluteFile) {
		if (Constants.THIS_MOD_JAR == null) return false;
		return absoluteFile.toAbsolutePath().normalize().equals(Constants.THIS_MOD_JAR.toAbsolutePath().normalize());
	}

	static LiveIdentity observeIdentity(ClientStorage storage) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		if (active == null) return LiveIdentity.empty();
		SelectionIntent selection = new ClientSelectionStore(storage.selectionFile()).get(active.modpackId).orElse(null);
		List<Tombstone> tombstones = new ArrayList<>();
		for (String modpackId : new ClientGenerationStore(storage).installedPackIds()) {
			List<String> deleted = storage.readOverlayState(modpackId).deletedPaths;
			if (!deleted.isEmpty()) tombstones.add(new Tombstone(modpackId, deleted));
		}
		return new LiveIdentity(active.modpackId, active.contentToken, active.detached, selection, tombstones);
	}

	boolean sameAs(InstanceTree other) {
		return sha1.equals(other.sha1);
	}

	private static ClientStorageJsons.InstanceTreeFields toFields(LiveIdentity identity, List<TrackedFile> files) {
		ClientStorageJsons.InstanceTreeFields fields = new ClientStorageJsons.InstanceTreeFields();
		fields.activeModpackId = identity.activeModpackId();
		fields.contentToken = identity.contentToken();
		fields.detached = identity.detached();
		SelectionIntent selection = identity.selection();
		fields.requestedGroups = selection == null ? List.of() : List.copyOf(selection.requestedGroups());
		fields.requestedCategories = selection == null ? List.of() : List.copyOf(selection.requestedCategories());
		fields.excludedGroups = selection == null ? List.of() : List.copyOf(selection.excludedGroups());
		List<ClientStorageJsons.InstanceTreeFields.TombstoneFields> tombstones = new ArrayList<>();
		for (Tombstone tombstone : identity.tombstones()) {
			ClientStorageJsons.InstanceTreeFields.TombstoneFields row = new ClientStorageJsons.InstanceTreeFields.TombstoneFields();
			row.modpackId = tombstone.modpackId();
			row.deletedPaths = tombstone.deletedPaths();
			tombstones.add(row);
		}
		fields.tombstones = tombstones;
		List<ClientStorageJsons.InstanceTreeFields.FileFields> fileFields = new ArrayList<>();
		for (TrackedFile file : files) {
			ClientStorageJsons.InstanceTreeFields.FileFields row = new ClientStorageJsons.InstanceTreeFields.FileFields();
			row.root = file.root().name();
			row.overlayPackId = file.overlayPackId();
			row.path = file.path();
			row.sha1 = file.sha1();
			row.size = file.size();
			fileFields.add(row);
		}
		fields.files = fileFields;
		return fields;
	}

	private static InstanceTree fromFields(ClientStorageJsons.InstanceTreeFields fields) {
		if (fields.files == null || fields.tombstones == null) throw new IllegalArgumentException("Instance tree is incomplete");
		List<TrackedFile> files = new ArrayList<>();
		for (ClientStorageJsons.InstanceTreeFields.FileFields file : fields.files)
			files.add(new TrackedFile(Root.valueOf(file.root), file.overlayPackId, file.path, file.sha1, file.size));
		List<Tombstone> tombstones = new ArrayList<>();
		for (ClientStorageJsons.InstanceTreeFields.TombstoneFields row : fields.tombstones)
			tombstones.add(new Tombstone(row.modpackId, row.deletedPaths == null ? List.of() : row.deletedPaths));
		SelectionIntent selection = fields.activeModpackId == null || fields.activeModpackId.isBlank()
				? null
				: new SelectionIntent(set(fields.requestedGroups), set(fields.requestedCategories), set(fields.excludedGroups));
		return of(new LiveIdentity(fields.activeModpackId, fields.contentToken, fields.detached, selection, tombstones), files);
	}

	private static NavigableSet<String> set(List<String> values) {
		return new TreeSet<>(values == null ? List.of() : values);
	}
}
