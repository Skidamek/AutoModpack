package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The validated client-owned projection of nested copies. The document holds one record per retained generation -
 * {@code selectionDigest} is which selection produced its entries, not part of its identity - so applying a selection
 * supersedes the record it replaces instead of adding one, and the file cannot grow with the number of selections a
 * player ever tries.
 *
 * <p>
 * Everything here is rebuildable state: an index that fails to parse is set aside as evidence and reads as empty, and
 * {@link #rebuildActive} re-derives the record the active generation needs from what is installed.
 */
public record GeneratedCopyState(String modpackId, String contentToken, String selectionDigest, List<Entry> entries) {
	private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::logicalPath);
	private static final Comparator<GeneratedCopyState> STATE_ORDER = Comparator.comparing(GeneratedCopyState::modpackId).thenComparing(GeneratedCopyState::contentToken);

	public GeneratedCopyState {
		modpackId = ModpackId.requireValid(modpackId);
		contentToken = HashUtils.requireDigest(contentToken, "generation ID");
		selectionDigest = HashUtils.requireDigest(selectionDigest, "generated-copy selection digest");
		List<Entry> sorted = new ArrayList<>(Objects.requireNonNull(entries, "generated-copy entries"));
		sorted.sort(ENTRY_ORDER);
		for (int i = 1; i < sorted.size(); i++)
			if (sorted.get(i - 1).logicalPath().equals(sorted.get(i).logicalPath())) throw new IllegalArgumentException("Generated-copy state contains duplicate paths");
		entries = List.copyOf(sorted);
	}

	public static GeneratedCopyState fromCopies(String modpackId, String contentToken, String selectionDigest, List<UpdatePlan.NestedCopy> copies) {
		return new GeneratedCopyState(modpackId, contentToken, selectionDigest, copies.stream().map(copy -> new Entry(copy.relativePath(), copy.sha1(), copy.size())).toList());
	}

	/**
	 * The state this selection produced for this generation, or an empty state when none was persisted: a superseded
	 * selection has no record left, and a generation no selection produced yet has none either. Every reader asks for the
	 * selection the active pointer committed under, which is the record the last apply wrote.
	 */
	public static GeneratedCopyState read(ClientStorage storage, String modpackId, String contentToken, String selectionDigest) throws IOException {
		GeneratedCopyState state = find(readIndex(storage), ModpackId.requireValid(modpackId), HashUtils.requireDigest(contentToken, "generation ID"));
		return state != null && state.selectionDigest().equals(HashUtils.requireDigest(selectionDigest, "generated-copy selection digest"))
				? state
				: new GeneratedCopyState(modpackId, contentToken, selectionDigest, List.of());
	}

	/** Every persisted generation's record. A pure read: a document that fails to parse is set aside as evidence and reads as empty. */
	public static List<GeneratedCopyState> loadAll(ClientStorage storage) throws IOException {
		return readIndex(storage);
	}

	public void write(ClientStorage storage) throws IOException {
		List<GeneratedCopyState> states = new ArrayList<>(loadAll(storage));
		states.removeIf(this::sameGeneration);
		states.add(this);
		saveIndex(storage, states);
	}

	public static void removeGeneration(ClientStorage storage, String modpackId, String contentToken) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		String token = HashUtils.requireDigest(contentToken, "generation ID");
		removeMatching(storage, state -> state.modpackId().equals(pack) && state.contentToken().equals(token));
	}

	public static void removePack(ClientStorage storage, String modpackId) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		removeMatching(storage, state -> state.modpackId().equals(pack));
	}

	public static void retainPackGenerations(ClientStorage storage, String modpackId, Set<String> keptTokens) throws IOException {
		String pack = ModpackId.requireValid(modpackId);
		removeMatching(storage, state -> state.modpackId().equals(pack) && !keptTokens.contains(state.contentToken()));
	}

	private static void removeMatching(ClientStorage storage, Predicate<GeneratedCopyState> doomed) throws IOException {
		List<GeneratedCopyState> states = new ArrayList<>(loadAll(storage));
		if (!states.removeIf(doomed)) return;
		saveIndex(storage, states);
	}

	/** The one identity of a record: its generation. The selection digest is the version of that record. */
	private boolean sameGeneration(GeneratedCopyState other) {
		return modpackId.equals(other.modpackId) && contentToken.equals(other.contentToken);
	}

	private static GeneratedCopyState find(List<GeneratedCopyState> states, String modpackId, String contentToken) {
		for (GeneratedCopyState state : states)
			if (state.modpackId().equals(modpackId) && state.contentToken().equals(contentToken)) return state;
		return null;
	}

	private static List<GeneratedCopyState> readIndex(ClientStorage storage) throws IOException {
		return ConfigTools.readState(storage.generatedCopiesFile(), ClientStorageJsons.ClientGeneratedCopiesIndexFields.class, "Generated-copy state", GeneratedCopyState::fromIndex).orElse(List.of());
	}

	private static void saveIndex(ClientStorage storage, List<GeneratedCopyState> states) throws IOException {
		List<GeneratedCopyState> sorted = new ArrayList<>(states);
		sorted.sort(STATE_ORDER);
		Path path = storage.generatedCopiesFile();
		if (sorted.isEmpty()) {
			Files.deleteIfExists(path);
			return;
		}
		ClientStorageJsons.ClientGeneratedCopiesIndexFields fields = new ClientStorageJsons.ClientGeneratedCopiesIndexFields();
		fields.states = sorted.stream().map(GeneratedCopyState::toFields).toList();
		ConfigTools.writeAtomicCompact(path, fields);
	}

	/**
	 * One record per generation. An index written before the file was keyed per generation carries a record per
	 * selection, so the last one for a generation wins here: no reader can observe which survived, because a reader only
	 * ever accepts an exact digest match, and pinning keeps the supersession instead of both sets.
	 */
	private static List<GeneratedCopyState> fromIndex(ClientStorageJsons.ClientGeneratedCopiesIndexFields fields) {
		if (fields == null || fields.schemaVersion != 1 || fields.states == null) throw new IllegalArgumentException("Generated-copy index is incomplete");
		Map<String, GeneratedCopyState> byGeneration = new LinkedHashMap<>();
		for (ClientStorageJsons.ClientGeneratedCopiesFields value : fields.states) {
			GeneratedCopyState state = fromFields(value);
			byGeneration.put(state.modpackId() + "/" + state.contentToken(), state);
		}
		return List.copyOf(byGeneration.values());
	}

	/**
	 * The rc.1/rc.2 per-identity tree, imported into the index once. It runs at storage initialization under the mutation
	 * lock, never from a reader: a read that rewrote durable state made every unlocked caller a writer, and two
	 * processes racing that index read-modify-write dropped each other's entries, which unpins installed bytes. Corrupt
	 * leftovers stay as evidence next to where they lived. Several selection files for one generation collapse to one
	 * record: the digest the active pointer committed under, otherwise the last file listed, matching {@link #fromIndex}.
	 */
	public static void migrateLegacyTree(ClientStorage storage) throws IOException {
		Path root = storage.generatedCopiesDirectory();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
		ClientStorageMutation.run(storage, () -> {
			List<GeneratedCopyState> states = new ArrayList<>(readIndex(storage));
			boolean changed = false;
			ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
			String preferredDigest = null;
			if (active != null) {
				SelectionIntent applied = new ClientGenerationStore(storage).appliedSelection(active.modpackId).orElse(null);
				if (applied != null) preferredDigest = UpdateTransaction.digest(applied);
			}
			try (Stream<Path> packs = Files.list(root)) {
				for (Path pack : packs.toList()) {
					if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS) || !ModpackId.isValid(pack.getFileName().toString())) continue;
					String modpackId = pack.getFileName().toString();
					try (Stream<Path> generations = Files.list(pack)) {
						for (Path generation : generations.toList()) {
							if (!Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS)) continue;
							String contentToken = generation.getFileName().toString();
							if (!HashUtils.isCanonicalSha1(contentToken)) continue;
							List<GeneratedCopyState> candidates = new ArrayList<>();
							try (Stream<Path> files = Files.list(generation)) {
								for (Path file : files.toList()) {
									String name = file.getFileName().toString();
									if (name.endsWith(DurableFiles.TEMPORARY_SUFFIX) || name.contains(DurableFiles.CORRUPT_ASIDE_MARKER)) continue;
									if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || name.length() != HashUtils.SHA1_HEX_LENGTH + ".json".length() || !name.endsWith(".json")
											|| !HashUtils.isCanonicalSha1(name.substring(0, HashUtils.SHA1_HEX_LENGTH)))
										continue;
									GeneratedCopyState imported = ConfigTools.readState(file, ClientStorageJsons.ClientGeneratedCopiesFields.class, "Generated-copy state",
											fields -> answeringTo(storage, modpackId, contentToken, name.substring(0, HashUtils.SHA1_HEX_LENGTH), file, fields)).orElse(null);
									if (imported == null) continue;
									candidates.add(imported);
									Files.deleteIfExists(file);
								}
							}
							if (candidates.isEmpty() || find(states, modpackId, contentToken) != null) continue;
							String digest = active != null && active.modpackId.equals(modpackId) && contentToken.equals(active.contentToken) ? preferredDigest : null;
							states.add(chooseImported(candidates, digest));
							changed = true;
						}
					}
				}
			}
			if (changed) saveIndex(storage, states);
			deleteEmptyLegacyDirectories(root);
			return null;
		});
	}

	private static GeneratedCopyState chooseImported(List<GeneratedCopyState> candidates, String preferredDigest) {
		if (preferredDigest != null) {
			for (GeneratedCopyState candidate : candidates)
				if (candidate.selectionDigest().equals(preferredDigest)) return candidate;
		}
		return candidates.get(candidates.size() - 1);
	}

	/**
	 * Re-derives the active generation's record from what is installed, so a set-aside index costs one boot of
	 * generated-copy knowledge instead of the whole instance's. The identity is the active pointer plus the selection it
	 * committed under; the entries are the reserved bundle file, the one game-directory path no manifest can ship. A
	 * generation that already has a record is left alone - the apply that wrote it is the authority, and re-deriving from
	 * the live file would replace a planned identity with a drifted one.
	 */
	public static void rebuildActive(ClientStorage storage) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null) return null;
			String contentToken = HashUtils.requireDigest(state.contentToken, "generation ID");
			if (find(readIndex(storage), state.modpackId, contentToken) != null) return null;
			SelectionIntent applied = new ClientGenerationStore(storage).appliedSelection(state.modpackId).orElse(null);
			if (applied == null) return null;
			String logicalPath = ModpackPathPolicy.generatedBundlePath();
			Path bundle = storage.gamePath(logicalPath);
			if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) return null;
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				String hash = FileIntegrity.identityHash(bundle, cache);
				if (hash == null) throw new IOException("Cannot hash the generated dependency bundle: " + bundle);
				GeneratedCopyState rebuilt = new GeneratedCopyState(state.modpackId, contentToken, UpdateTransaction.digest(applied), List.of(new Entry(logicalPath, hash, Files.size(bundle))));
				rebuilt.write(storage);
				LOGGER.warn("Rebuilt the generated-copy state of {} from the installed bundle; the index had been lost", state.modpackId);
			}
			return null;
		});
	}

	private static void deleteEmptyLegacyDirectories(Path root) throws IOException {
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
		try (Stream<Path> packs = Files.list(root)) {
			for (Path pack : packs.toList()) {
				if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) continue;
				try (Stream<Path> generations = Files.list(pack)) {
					for (Path generation : generations.toList()) {
						if (Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS) && directoryIsEmpty(generation)) FileTrees.delete(generation);
					}
				}
				if (directoryIsEmpty(pack)) FileTrees.delete(pack);
			}
		}
		if (directoryIsEmpty(root)) FileTrees.delete(root);
	}

	private static boolean directoryIsEmpty(Path directory) throws IOException {
		try (Stream<Path> children = Files.list(directory)) {
			return children.findAny().isEmpty();
		}
	}

	private static GeneratedCopyState answeringTo(ClientStorage storage, String modpackId, String contentToken, String selectionDigest, Path path, ClientStorageJsons.ClientGeneratedCopiesFields fields) {
		GeneratedCopyState state = fromFields(fields);
		if (!state.modpackId().equals(ModpackId.requireValid(modpackId)) || !state.contentToken().equals(HashUtils.requireDigest(contentToken, "generation ID"))
				|| !state.selectionDigest().equals(HashUtils.requireDigest(selectionDigest, "generated-copy selection digest"))
				|| !path.equals(storage.legacyGeneratedCopiesFile(modpackId, contentToken, selectionDigest)))
			throw new IllegalArgumentException("Generated-copy state does not answer to its path: " + path);
		return state;
	}

	public ClientStorageJsons.ClientGeneratedCopiesFields toFields() {
		ClientStorageJsons.ClientGeneratedCopiesFields fields = new ClientStorageJsons.ClientGeneratedCopiesFields();
		fields.modpackId = modpackId;
		fields.contentToken = contentToken;
		fields.selectionDigest = selectionDigest;
		fields.entries = entries.stream().map(entry -> {
			ClientStorageJsons.ClientGeneratedCopiesFields.EntryFields value = new ClientStorageJsons.ClientGeneratedCopiesFields.EntryFields();
			value.logicalPath = entry.logicalPath();
			value.sha1 = entry.sha1();
			value.size = entry.size();
			return value;
		}).toList();
		return fields;
	}

	public static GeneratedCopyState fromFields(ClientStorageJsons.ClientGeneratedCopiesFields fields) {
		if (fields == null || fields.schemaVersion != 1 || fields.entries == null) throw new IllegalArgumentException("Generated-copy state fields are incomplete");
		List<Entry> entries = new ArrayList<>();
		for (ClientStorageJsons.ClientGeneratedCopiesFields.EntryFields value : fields.entries) {
			if (value == null) throw new IllegalArgumentException("Generated-copy state contains a null entry");
			entries.add(new Entry(value.logicalPath, value.sha1, value.size));
		}
		return new GeneratedCopyState(fields.modpackId, fields.contentToken, fields.selectionDigest, entries);
	}

	public List<UpdatePlan.NestedCopy> nestedCopies() {
		return entries.stream().map(entry -> new UpdatePlan.NestedCopy(entry.logicalPath(), entry.sha1(), entry.size())).toList();
	}

	public record Entry(String logicalPath, String sha1, long size) {
		public Entry {
			logicalPath = LogicalPath.requireCanonical(logicalPath);
			if (!ModpackPathPolicy.isModPath(logicalPath)) throw new IllegalArgumentException("Generated-copy path is outside the mods directory");
			if (!HashUtils.isSha1(sha1)) throw new IllegalArgumentException("Generated-copy SHA-1 is invalid");
			sha1 = HashUtils.normalizeSha1(sha1);
			if (size < 0) throw new IllegalArgumentException("Generated-copy size is invalid");
		}
	}
}
