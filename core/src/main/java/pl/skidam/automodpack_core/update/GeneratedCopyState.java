package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The validated client-owned projection of nested copies for one pack generation and group selection. */
public record GeneratedCopyState(String modpackId, String generationId, String selectionDigest, List<Entry> entries) {
	private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::logicalPath);

	public GeneratedCopyState {
		modpackId = ModpackId.requireValid(modpackId);
		generationId = requireDigest(generationId, "generation ID");
		selectionDigest = requireDigest(selectionDigest, "generated-copy selection digest");
		List<Entry> sorted = new ArrayList<>(Objects.requireNonNull(entries, "generated-copy entries"));
		sorted.sort(ENTRY_ORDER);
		for (int i = 1; i < sorted.size(); i++)
			if (sorted.get(i - 1).logicalPath().equals(sorted.get(i).logicalPath()))
				throw new IllegalArgumentException("Generated-copy state contains duplicate paths");
		entries = List.copyOf(sorted);
	}

	public static GeneratedCopyState fromCopies(String modpackId, String generationId, String selectionDigest, List<UpdatePlan.NestedCopy> copies) {
		return new GeneratedCopyState(modpackId, generationId, selectionDigest,
				copies.stream().map(copy -> new Entry(copy.relativePath(), copy.sha1(), copy.size())).toList());
	}

	public static GeneratedCopyState read(ClientStorage storage, String modpackId, String generationId, String selectionDigest) throws IOException {
		Path path = storage.generatedCopiesFile(modpackId, generationId, selectionDigest);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new GeneratedCopyState(modpackId, generationId, selectionDigest, List.of());
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Generated-copy state is not a regular file: " + path);
		try {
			ClientStorageJsons.ClientGeneratedCopiesFields fields = ConfigTools.read(path, ClientStorageJsons.ClientGeneratedCopiesFields.class)
					.orElseThrow(() -> new IOException("Generated-copy state is empty: " + path));
			GeneratedCopyState state = fromFields(fields);
			if (!state.modpackId().equals(ModpackId.requireValid(modpackId)) || !state.generationId().equals(requireDigest(generationId, "generation ID"))
					|| !state.selectionDigest().equals(requireDigest(selectionDigest, "generated-copy selection digest")))
				throw new IOException("Generated-copy state identity is invalid: " + path);
			return state;
		} catch (RuntimeException e) {
			throw new IOException("Generated-copy state is invalid: " + path, e);
		}
	}

	public void write(ClientStorage storage) throws IOException {
		ClientStorageJsons.ClientGeneratedCopiesFields fields = toFields();
		Path path = storage.generatedCopiesFile(modpackId, generationId, selectionDigest);
		Files.createDirectories(path.getParent());
		ConfigTools.writeAtomic(path, fields);
	}

	public void delete(ClientStorage storage) throws IOException {
		Files.deleteIfExists(storage.generatedCopiesFile(modpackId, generationId, selectionDigest));
	}

	public ClientStorageJsons.ClientGeneratedCopiesFields toFields() {
		ClientStorageJsons.ClientGeneratedCopiesFields fields = new ClientStorageJsons.ClientGeneratedCopiesFields();
		fields.modpackId = modpackId;
		fields.generationId = generationId;
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
		return new GeneratedCopyState(fields.modpackId, fields.generationId, fields.selectionDigest, entries);
	}

	public List<UpdatePlan.NestedCopy> nestedCopies() {
		return entries.stream().map(entry -> new UpdatePlan.NestedCopy(entry.logicalPath(), entry.sha1(), entry.size(), Set.of())).toList();
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

	private static String requireDigest(String value, String description) {
		if (!HashUtils.isSha1(value)) throw new IllegalArgumentException("Invalid " + description);
		return HashUtils.normalizeSha1(value);
	}
}
