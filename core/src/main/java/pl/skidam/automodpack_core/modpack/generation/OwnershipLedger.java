package pl.skidam.automodpack_core.modpack.generation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.PathFold;

/** Immutable cumulative ownership projection for one modpack lineage. */
public record OwnershipLedger(String modpackId, NavigableMap<String, Entry> entries, String digest) {
	// Only the computing constructor passes this identity token with its fresh, natural-order TreeMap.
	// It is replaced before publication; an equal-content external/wire string is not this token.
	private static final String COMPUTE_DIGEST = new String();
	private static final Comparator<Content> CONTENT_ORDER = Comparator.comparing(Content::sha1).thenComparingLong(Content::size);
	/**
	 * One validation per parsed ledger revision. The same fields object is handed to the digest check, the target
	 * identity, the manifest and the transaction gates; they all ask about the same bytes. The memo is keyed by that
	 * object and guarded by a structural fingerprint, so a mutated document still takes the full validation path.
	 */
	private static final int MEMO_CAP = 64;
	private static final Map<GenerationJsons.OwnershipLedgerFields, Validated> MEMO = Collections.synchronizedMap(new WeakHashMap<>());

	private record Validated(long fingerprint, OwnershipLedger ledger) {}

	public enum Status {
		PRESENT, TOMBSTONE
	}

	public record Content(String sha1, long size) {
		public Content {
			if (!HashUtils.isCanonicalSha1(sha1)) throw new IllegalArgumentException("Invalid ledger SHA-1");
			if (size < 0) throw new IllegalArgumentException("Negative ledger content size");
		}
	}

	public record Entry(String logicalPath, NavigableSet<Content> historicalHashes, NavigableSet<String> historicalGroupIds, Status currentStatus) {
		public Entry {
			logicalPath = LogicalPath.normalize(logicalPath);
			historicalHashes = immutableContents(historicalHashes);
			historicalGroupIds = immutableStrings(historicalGroupIds);
			currentStatus = Objects.requireNonNull(currentStatus, "current status");
			if (historicalHashes.isEmpty()) throw new IllegalArgumentException("Ledger entry has no historical hashes");
			if (historicalGroupIds.isEmpty()) throw new IllegalArgumentException("Ledger entry has no historical groups");
		}

		private static NavigableSet<Content> immutableContents(Collection<Content> values) {
			TreeSet<Content> result = new TreeSet<>(CONTENT_ORDER);
			if (values != null) result.addAll(values);
			return Collections.unmodifiableNavigableSet(result);
		}

		private static NavigableSet<String> immutableStrings(Collection<String> values) {
			TreeSet<String> result = new TreeSet<>();
			if (values != null) result.addAll(values);
			return Collections.unmodifiableNavigableSet(result);
		}
	}

	public OwnershipLedger {
		ModpackId.requireValid(modpackId);
		boolean computing = digest == COMPUTE_DIGEST;
		TreeMap<String, Entry> sorted = computing ? (TreeMap<String, Entry>) entries : new TreeMap<>();
		if (!computing && entries != null) sorted.putAll(entries);
		for (var entry : sorted.entrySet()) {
			if (!entry.getKey().equals(entry.getValue().logicalPath())) throw new IllegalArgumentException("Ledger entry key does not match logical path");
		}
		entries = Collections.unmodifiableNavigableMap(sorted);
		String expected = digestSorted(modpackId, sorted);
		if (computing) digest = expected;
		if (digest == null || !digest.equals(expected)) throw new IllegalArgumentException("Ledger digest does not match content");
	}

	public OwnershipLedger(String modpackId, Map<String, Entry> entries) {
		this(modpackId, toNavigableMap(entries), COMPUTE_DIGEST);
	}

	public GenerationJsons.OwnershipLedgerFields toFields() {
		GenerationJsons.OwnershipLedgerFields fields = new GenerationJsons.OwnershipLedgerFields();
		fields.modpackId = modpackId;
		fields.entries = new ArrayList<>();
		for (Entry entry : entries.values()) {
			GenerationJsons.OwnershipLedgerFields.EntryFields serialized = new GenerationJsons.OwnershipLedgerFields.EntryFields();
			serialized.logicalPath = entry.logicalPath();
			serialized.historicalHashes = entry.historicalHashes().stream()
					.map(value -> new GenerationJsons.OwnershipLedgerFields.ContentFields(value.sha1(), value.size())).toList();
			serialized.historicalGroupIds = new TreeSet<>(entry.historicalGroupIds());
			serialized.currentStatus = entry.currentStatus().name();
			fields.entries.add(serialized);
		}
		fields.digest = digest;
		return fields;
	}

	public static OwnershipLedger fromFields(GenerationJsons.OwnershipLedgerFields fields) {
		if (fields == null || fields.entries == null) throw new IllegalArgumentException("Ownership ledger is missing");
		long fingerprint = fieldsFingerprint(fields);
		Validated memo = MEMO.get(fields);
		if (memo != null && memo.fingerprint() == fingerprint) return memo.ledger();
		NavigableMap<String, Entry> entries = new TreeMap<>();
		for (GenerationJsons.OwnershipLedgerFields.EntryFields serialized : fields.entries) {
			if (serialized == null || serialized.historicalHashes == null) throw new IllegalArgumentException("Ownership ledger entry is incomplete");
			NavigableSet<Content> hashes = new TreeSet<>(CONTENT_ORDER);
			for (GenerationJsons.OwnershipLedgerFields.ContentFields content : serialized.historicalHashes) {
				if (content == null) throw new IllegalArgumentException("Ownership ledger content is incomplete");
				hashes.add(new Content(content.sha1, content.size));
			}
			Entry entry = new Entry(serialized.logicalPath, hashes, new TreeSet<>(serialized.historicalGroupIds), parseStatus(serialized.currentStatus));
			if (entries.put(entry.logicalPath(), entry) != null) throw new IllegalArgumentException("Duplicate ledger path: " + entry.logicalPath());
		}
		OwnershipLedger ledger = new OwnershipLedger(fields.modpackId, entries, fields.digest);
		if (MEMO.size() >= MEMO_CAP) MEMO.clear();
		MEMO.put(fields, new Validated(fingerprint, ledger));
		return ledger;
	}

	/**
	 * A cheap fingerprint of every field the digest covers, used only to prove that the object a memo was built from
	 * still holds those bytes. String hash codes are cached, so a hit costs one pass of primitive reads instead of a
	 * full canonical digest, and any mutation at all takes the full validation path.
	 */
	private static long fieldsFingerprint(GenerationJsons.OwnershipLedgerFields fields) {
		long hash = 0xcbf29ce484222325L;
		hash = mix(hash, fields.modpackId);
		hash = mix(hash, fields.digest);
		hash = mix(hash, fields.entries.size());
		for (GenerationJsons.OwnershipLedgerFields.EntryFields entry : fields.entries) {
			if (entry == null) {
				hash = mix(hash, 0);
				continue;
			}
			hash = mix(hash, entry.logicalPath);
			hash = mix(hash, entry.currentStatus);
			hash = mix(hash, entry.historicalHashes == null ? -1 : entry.historicalHashes.size());
			if (entry.historicalHashes != null) for (GenerationJsons.OwnershipLedgerFields.ContentFields content : entry.historicalHashes) {
				if (content == null) {
					hash = mix(hash, 0);
					continue;
				}
				hash = mix(hash, content.sha1);
				hash = mix(hash, content.size);
			}
			hash = mix(hash, entry.historicalGroupIds == null ? -1 : entry.historicalGroupIds.size());
			if (entry.historicalGroupIds != null) for (String group : entry.historicalGroupIds) hash = mix(hash, group);
		}
		return hash;
	}

	private static long mix(long hash, String value) {
		return mix(hash, value == null ? 0 : value.hashCode());
	}

	private static long mix(long hash, long value) {
		hash ^= value;
		hash *= 0x100000001b3L;
		return Long.rotateLeft(hash, 27);
	}

	public static OwnershipLedger empty(String modpackId) {
		return new OwnershipLedger(modpackId, Map.of());
	}

	/** Mutable reconstruction state used by the generation store while replaying compact deltas. */
	public static Builder builder(String modpackId) {
		return new Builder(empty(modpackId));
	}

	/** Mutable reconstruction state seeded from one already materialized ledger. */
	public static Builder builder(OwnershipLedger ledger) {
		return new Builder(Objects.requireNonNull(ledger));
	}

	public static final class Builder {
		private final String modpackId;
		private final TreeMap<String, Entry> entries;

		private Builder(OwnershipLedger base) {
			modpackId = base.modpackId();
			entries = new TreeMap<>(base.entries());
		}

		public Builder apply(OwnershipDelta delta) {
			Objects.requireNonNull(delta, "ownership delta");
			if (!modpackId.equals(delta.modpackId())) throw new IllegalArgumentException("Delta and ledger modpack IDs disagree");
			for (OwnershipDelta.Change change : delta.changes().values()) apply(change);
			return this;
		}

		public OwnershipLedger build() {
			return new OwnershipLedger(modpackId, entries);
		}

		NavigableMap<String, Entry> entriesView() {
			return Collections.unmodifiableNavigableMap(entries);
		}

		private void apply(OwnershipDelta.Change change) {
			String path = change.logicalPath();
			Entry old = entries.get(path);
			NavigableSet<Content> hashes = old == null ? new TreeSet<>(CONTENT_ORDER) : new TreeSet<>(old.historicalHashes());
			NavigableSet<String> groups = old == null ? new TreeSet<>() : new TreeSet<>(old.historicalGroupIds());
			switch (change.kind()) {
				case ADDED -> {
					if (old != null) throw new IllegalArgumentException("Added ownership path already exists: " + path);
					hashes.addAll(change.contents());
					groups.addAll(change.groupIds());
					entries.put(path, new Entry(path, hashes, groups, Status.PRESENT));
				}
				case REPLACED -> {
					requirePresent(old, path, change.kind());
					hashes.addAll(change.contents());
					groups.addAll(change.groupIds());
					entries.put(path, new Entry(path, hashes, groups, Status.PRESENT));
				}
				case RETURNED -> {
					if (old == null || old.currentStatus() != Status.TOMBSTONE)
						throw new IllegalArgumentException("Returned ownership path is not a tombstone: " + path);
					hashes.addAll(change.contents());
					groups.addAll(change.groupIds());
					entries.put(path, new Entry(path, hashes, groups, Status.PRESENT));
				}
				case GROUP_OWNERSHIP_CHANGED -> {
					requirePresent(old, path, change.kind());
					hashes.addAll(change.contents());
					groups.addAll(change.groupIds());
					entries.put(path, new Entry(path, hashes, groups, Status.PRESENT));
				}
				case REMOVED -> {
					requirePresent(old, path, change.kind());
					entries.put(path, new Entry(path, old.historicalHashes(), old.historicalGroupIds(), Status.TOMBSTONE));
				}
			}
		}

		private static void requirePresent(Entry entry, String path, OwnershipDelta.Kind kind) {
			if (entry == null || entry.currentStatus() != Status.PRESENT)
				throw new IllegalArgumentException(kind + " ownership path is not present: " + path);
		}
	}

	/**
	 * The tombstones a client holding {@code since} has not seen: all of them for another pack or none, else paths deleted,
	 * or shipped again and deleted, after it. One whose spelling is, or folds onto, a path the client ships is that file on
	 * an insensitive disk, so it never names a file to delete.
	 */
	public List<Entry> tombstonesSince(OwnershipLedger since, Collection<String> shippedPaths) {
		Set<String> shipped = new HashSet<>();
		for (String path : shippedPaths) shipped.add(PathFold.key(LogicalPath.normalize(path)));
		List<Entry> unseen = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.currentStatus() != Status.TOMBSTONE || shipped.contains(PathFold.key(entry.logicalPath()))) continue;
			Entry seen = since == null || !since.modpackId().equals(modpackId) ? null : since.entries().get(entry.logicalPath());
			if (seen != null && seen.currentStatus() == Status.TOMBSTONE && seen.historicalHashes().containsAll(entry.historicalHashes())) continue;
			unseen.add(entry);
		}
		return unseen;
	}

	public static OwnershipLedger materialize(OwnershipLedger parent, GroupManifest manifest) {
		Objects.requireNonNull(parent, "parent");
		Objects.requireNonNull(manifest, "manifest");
		if (!parent.modpackId().equals(manifest.modpackId())) throw new IllegalArgumentException("Ledger and catalogue modpack IDs disagree");
		return builder(parent).apply(OwnershipDelta.between(parent, manifest)).build();
	}

	public static String digest(String modpackId, Map<String, Entry> entries) {
		TreeMap<String, Entry> sorted = new TreeMap<>();
		if (entries != null) sorted.putAll(entries);
		return digestSorted(modpackId, sorted);
	}

	private static String digestSorted(String modpackId, NavigableMap<String, Entry> sorted) {
		CanonicalEncoder encoder = new CanonicalEncoder().string("automodpack-ownership-ledger-v1").string(modpackId);
		encoder.integer(sorted.size());
		for (Entry entry : sorted.values()) {
			encoder.string(entry.logicalPath()).integer(entry.historicalHashes().size());
			for (Content content : entry.historicalHashes()) encoder.string(content.sha1()).longValue(content.size());
			encoder.integer(entry.historicalGroupIds().size());
			for (String group : entry.historicalGroupIds()) encoder.string(group);
			/* Publication IDs are provenance metadata. Excluding them avoids a digest/identity cycle. */
			encoder.string(entry.currentStatus().name());
		}
		return encoder.sha1();
	}

	private static NavigableMap<String, Entry> toNavigableMap(Map<String, Entry> entries) {
		return entries == null ? new TreeMap<>() : new TreeMap<>(entries);
	}

	private static Status parseStatus(String value) {
		try {
			return Status.valueOf(value);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid ownership ledger status", e);
		}
	}

}
