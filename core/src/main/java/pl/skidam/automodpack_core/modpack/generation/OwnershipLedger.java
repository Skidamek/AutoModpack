package pl.skidam.automodpack_core.modpack.generation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;

/** Immutable cumulative ownership projection for one modpack lineage. */
public record OwnershipLedger(String modpackId, NavigableMap<String, Entry> entries, String digest) {
	private static final Comparator<Content> CONTENT_ORDER = Comparator.comparing(Content::sha1).thenComparingLong(Content::size);
	private static final String PROVISIONAL_GENERATION = "0".repeat(40);

	public enum Status {
		PRESENT, TOMBSTONE
	}

	public record Content(String sha1, long size) {
		public Content {
			if (sha1 == null || !sha1.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid ledger SHA-1");
			if (size < 0) throw new IllegalArgumentException("Negative ledger content size");
		}
	}

	public record Entry(String logicalPath, NavigableSet<Content> historicalHashes, NavigableSet<String> historicalGroupIds,
			String firstPublishedGenerationId, String lastPublishedGenerationId, Status currentStatus) {
		public Entry {
			logicalPath = LogicalPath.normalize(logicalPath);
			historicalHashes = immutableContents(historicalHashes);
			historicalGroupIds = immutableStrings(historicalGroupIds);
			firstPublishedGenerationId = requireGenerationReference(firstPublishedGenerationId, "first published generation ID");
			lastPublishedGenerationId = requireGenerationReference(lastPublishedGenerationId, "last published generation ID");
			currentStatus = Objects.requireNonNull(currentStatus, "current status");
			if (historicalHashes.isEmpty()) throw new IllegalArgumentException("Ledger entry has no historical hashes");
			if (historicalGroupIds.isEmpty()) throw new IllegalArgumentException("Ledger entry has no historical groups");
		}

		public Entry(String logicalPath, Collection<Content> historicalHashes, Collection<String> historicalGroupIds,
				String firstPublishedGenerationId, String lastPublishedGenerationId, Status currentStatus) {
			this(logicalPath, immutableContents(historicalHashes), immutableStrings(historicalGroupIds), firstPublishedGenerationId, lastPublishedGenerationId, currentStatus);
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
		TreeMap<String, Entry> sorted = new TreeMap<>();
		if (entries != null) sorted.putAll(entries);
		for (var entry : sorted.entrySet()) {
			if (!entry.getKey().equals(entry.getValue().logicalPath())) throw new IllegalArgumentException("Ledger entry key does not match logical path");
		}
		entries = Collections.unmodifiableNavigableMap(sorted);
		String expected = digest(modpackId, entries);
		if (digest == null || !digest.equals(expected)) throw new IllegalArgumentException("Ledger digest does not match content");
	}

	public OwnershipLedger(String modpackId, Map<String, Entry> entries) {
		this(modpackId, toNavigableMap(entries), digest(modpackId, entries));
	}

	public Jsons.OwnershipLedgerFields toFields() {
		Jsons.OwnershipLedgerFields fields = new Jsons.OwnershipLedgerFields();
		fields.modpackId = modpackId;
		fields.entries = new ArrayList<>();
		for (Entry entry : entries.values()) {
			Jsons.OwnershipLedgerFields.EntryFields serialized = new Jsons.OwnershipLedgerFields.EntryFields();
			serialized.logicalPath = entry.logicalPath();
			serialized.historicalHashes = entry.historicalHashes().stream()
					.map(value -> new Jsons.OwnershipLedgerFields.ContentFields(value.sha1(), value.size())).toList();
			serialized.historicalGroupIds = new TreeSet<>(entry.historicalGroupIds());
			serialized.firstPublishedGenerationId = entry.firstPublishedGenerationId();
			serialized.lastPublishedGenerationId = entry.lastPublishedGenerationId();
			serialized.currentStatus = entry.currentStatus().name();
			fields.entries.add(serialized);
		}
		fields.digest = digest;
		return fields;
	}

	public static OwnershipLedger fromFields(Jsons.OwnershipLedgerFields fields) {
		if (fields == null || fields.entries == null) throw new IllegalArgumentException("Ownership ledger is missing");
		Map<String, Entry> entries = new TreeMap<>();
		for (Jsons.OwnershipLedgerFields.EntryFields serialized : fields.entries) {
			if (serialized == null || serialized.historicalHashes == null) throw new IllegalArgumentException("Ownership ledger entry is incomplete");
			Set<Content> hashes = new TreeSet<>(CONTENT_ORDER);
			for (Jsons.OwnershipLedgerFields.ContentFields content : serialized.historicalHashes) {
				if (content == null) throw new IllegalArgumentException("Ownership ledger content is incomplete");
				hashes.add(new Content(content.sha1, content.size));
			}
			Entry entry = new Entry(serialized.logicalPath, hashes, serialized.historicalGroupIds, serialized.firstPublishedGenerationId,
					serialized.lastPublishedGenerationId, parseStatus(serialized.currentStatus));
			if (entries.put(entry.logicalPath(), entry) != null) throw new IllegalArgumentException("Duplicate ledger path: " + entry.logicalPath());
		}
		return new OwnershipLedger(fields.modpackId, new TreeMap<>(entries), fields.digest);
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

		public Builder apply(OwnershipDelta delta, String generationId) {
			Objects.requireNonNull(delta, "ownership delta");
			String currentGeneration = requireGenerationReference(generationId, "generation ID");
			if (!modpackId.equals(delta.modpackId())) throw new IllegalArgumentException("Delta and ledger modpack IDs disagree");
			for (OwnershipDelta.Change change : delta.changes().values()) apply(change, currentGeneration);
			return this;
		}

		public OwnershipLedger build() {
			return new OwnershipLedger(modpackId, entries);
		}

		NavigableMap<String, Entry> entriesView() {
			return Collections.unmodifiableNavigableMap(entries);
		}

		private void apply(OwnershipDelta.Change change, String generationId) {
			String path = change.logicalPath();
			Entry old = entries.get(path);
			Set<Content> hashes = old == null ? new TreeSet<>(CONTENT_ORDER) : new TreeSet<>(old.historicalHashes());
			Set<String> groups = old == null ? new TreeSet<>() : new TreeSet<>(old.historicalGroupIds());
			switch (change.kind()) {
				case ADDED -> {
					if (old != null) throw new IllegalArgumentException("Added ownership path already exists: " + path);
					hashes.addAll(change.contents());
					groups.addAll(change.groupIds());
					entries.put(path, new Entry(path, hashes, groups, generationId, generationId, Status.PRESENT));
				}
				case REPLACED -> {
					requirePresent(old, path, change.kind());
					hashes.addAll(change.contents());
					groups.addAll(change.groupIds());
					entries.put(path, new Entry(path, hashes, groups, old.firstPublishedGenerationId(), generationId, Status.PRESENT));
				}
				case RETURNED -> {
					if (old == null || old.currentStatus() != Status.TOMBSTONE)
						throw new IllegalArgumentException("Returned ownership path is not a tombstone: " + path);
					hashes.addAll(change.contents());
					groups.addAll(change.groupIds());
					entries.put(path, new Entry(path, hashes, groups, old.firstPublishedGenerationId(), generationId, Status.PRESENT));
				}
				case GROUP_OWNERSHIP_CHANGED -> {
					requirePresent(old, path, change.kind());
					hashes.addAll(change.contents());
					groups.addAll(change.groupIds());
					entries.put(path, new Entry(path, hashes, groups, old.firstPublishedGenerationId(), generationId, Status.PRESENT));
				}
				case REMOVED -> {
					requirePresent(old, path, change.kind());
					entries.put(path, new Entry(path, old.historicalHashes(), old.historicalGroupIds(), old.firstPublishedGenerationId(), generationId, Status.TOMBSTONE));
				}
			}
		}

		private static void requirePresent(Entry entry, String path, OwnershipDelta.Kind kind) {
			if (entry == null || entry.currentStatus() != Status.PRESENT)
				throw new IllegalArgumentException(kind + " ownership path is not present: " + path);
		}
	}

	public static OwnershipLedger materialize(OwnershipLedger parent, GroupManifest manifest, String generationId) {
		Objects.requireNonNull(parent, "parent");
		Objects.requireNonNull(manifest, "manifest");
		if (!parent.modpackId().equals(manifest.modpackId())) throw new IllegalArgumentException("Ledger and catalogue modpack IDs disagree");
		return builder(parent).apply(OwnershipDelta.between(parent, manifest), generationId).build();
	}

	public static OwnershipLedger materializeWithoutGeneration(OwnershipLedger parent, GroupManifest manifest) {
		return materialize(parent, manifest, PROVISIONAL_GENERATION);
	}

	public static OwnershipLedger rebuild(List<GenerationRecord> orderedChain) {
		if (orderedChain == null || orderedChain.isEmpty()) throw new IllegalArgumentException("Generation chain is empty");
		GenerationRecord first = Objects.requireNonNull(orderedChain.get(0), "Generation chain contains null record");
		OwnershipLedger ledger = empty(first.manifest().modpackId());
		String previousGenerationId = GenerationMetadata.ROOT_PARENT;
		for (GenerationRecord record : orderedChain) {
			Objects.requireNonNull(record, "Generation chain contains null record");
			if (!ledger.modpackId().equals(record.manifest().modpackId())) throw new IllegalArgumentException("Generation chain changes modpack ID");
			if (!record.metadata().parentGenerationId().equals(previousGenerationId))
				throw new IllegalArgumentException("Generation chain is not ordered by parent links");
			OwnershipLedger expected = materialize(ledger, record.manifest(), record.metadata().generationId());
			if (!expected.equals(record.ownershipLedger())) throw new IllegalArgumentException("Generation ledger does not match ordered parent state");
			ledger = expected;
			previousGenerationId = record.metadata().generationId();
		}
		return ledger;
	}

	public static String digest(String modpackId, Map<String, Entry> entries) {
		CanonicalEncoder encoder = new CanonicalEncoder().string("automodpack-ownership-ledger-v1").string(modpackId);
		TreeMap<String, Entry> sorted = new TreeMap<>();
		if (entries != null) sorted.putAll(entries);
		encoder.integer(sorted.size());
		for (Entry entry : sorted.values()) {
			encoder.string(entry.logicalPath()).integer(entry.historicalHashes().size());
			for (Content content : entry.historicalHashes()) encoder.string(content.sha1()).longValue(content.size());
			encoder.integer(entry.historicalGroupIds().size());
			for (String group : entry.historicalGroupIds()) encoder.string(group);
			/* Publication IDs are provenance metadata. Excluding them avoids a digest/identity cycle. */
			encoder.string(entry.currentStatus().name());
		}
		return GenerationIdentity.sha1Bytes(encoder.bytes());
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

	private static String requireGenerationReference(String value, String name) {
		if (value == null || !value.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid " + name);
		return value;
	}

}
