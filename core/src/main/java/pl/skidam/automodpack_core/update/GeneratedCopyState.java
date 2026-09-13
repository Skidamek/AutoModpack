package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
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
public record GeneratedCopyState(String modpackId, String contentToken, String selectionDigest, List<Entry> entries) {
	private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::logicalPath);

	public GeneratedCopyState {
		modpackId = ModpackId.requireValid(modpackId);
		contentToken = HashUtils.requireDigest(contentToken, "generation ID");
		selectionDigest = HashUtils.requireDigest(selectionDigest, "generated-copy selection digest");
		List<Entry> sorted = new ArrayList<>(Objects.requireNonNull(entries, "generated-copy entries"));
		sorted.sort(ENTRY_ORDER);
		for (int i = 1; i < sorted.size(); i++)
			if (sorted.get(i - 1).logicalPath().equals(sorted.get(i).logicalPath()))
				throw new IllegalArgumentException("Generated-copy state contains duplicate paths");
		entries = List.copyOf(sorted);
	}

	public static GeneratedCopyState fromCopies(String modpackId, String contentToken, String selectionDigest, List<UpdatePlan.NestedCopy> copies) {
		return new GeneratedCopyState(modpackId, contentToken, selectionDigest,
				copies.stream().map(copy -> new Entry(copy.relativePath(), copy.sha1(), copy.size())).toList());
	}

	/**
	 * The state at the given identity, or an empty state when none was persisted yet; a persisted file that fails to
	 * parse or does not answer to its own path is set aside as evidence and reads as empty, so a state written by a
	 * build with another format can never block the client again.
	 */
	public static GeneratedCopyState read(ClientStorage storage, String modpackId, String contentToken, String selectionDigest) throws IOException {
		Path path = storage.generatedCopiesFile(modpackId, contentToken, selectionDigest);
		return ConfigTools.readState(path, ClientStorageJsons.ClientGeneratedCopiesFields.class, "Generated-copy state",
				fields -> answeringTo(modpackId, contentToken, selectionDigest, path, fields)).orElse(new GeneratedCopyState(modpackId, contentToken, selectionDigest, List.of()));
	}

	private static GeneratedCopyState answeringTo(String modpackId, String contentToken, String selectionDigest, Path path, ClientStorageJsons.ClientGeneratedCopiesFields fields) {
		GeneratedCopyState state = fromFields(fields);
		if (!state.modpackId().equals(ModpackId.requireValid(modpackId)) || !state.contentToken().equals(HashUtils.requireDigest(contentToken, "generation ID"))
				|| !state.selectionDigest().equals(HashUtils.requireDigest(selectionDigest, "generated-copy selection digest")))
			throw new IllegalArgumentException("Generated-copy state does not answer to its path: " + path);
		return state;
	}

	public void write(ClientStorage storage) throws IOException {
		ClientStorageJsons.ClientGeneratedCopiesFields fields = toFields();
		Path path = storage.generatedCopiesFile(modpackId, contentToken, selectionDigest);
		Files.createDirectories(path.getParent());
		ConfigTools.writeAtomic(path, fields);
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
}
