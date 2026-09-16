package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;

class OwnershipLedgerTest {
	private static final String HASH_A = "1111111111111111111111111111111111111111";
	private static final String HASH_B = "2222222222222222222222222222222222222222";

	@Test
	void materializesHistoricalHashesAndTombstonesAcrossOrderedGenerations() {
		GroupManifest firstManifest = manifest(file(HASH_A, 1));
		GenerationRecord first = GenerationRecord.create(firstManifest, null, Instant.parse("2026-01-01T00:00:00Z"), "");
		GenerationRecord replaced = GenerationRecord.create(manifest(file(HASH_B, 2)), first, Instant.parse("2026-01-02T00:00:00Z"), "");
		GenerationRecord removed = GenerationRecord.create(manifest(), replaced, Instant.parse("2026-01-03T00:00:00Z"), "");
		GenerationRecord returned = GenerationRecord.create(firstManifest, removed, Instant.parse("2026-01-04T00:00:00Z"), "");

		OwnershipLedger.Entry replacedEntry = replaced.ownershipLedger().entries().get("config/example.txt");
		assertEquals(OwnershipLedger.Status.PRESENT, replacedEntry.currentStatus());
		assertEquals(Set.of(new OwnershipLedger.Content(HASH_A, 1), new OwnershipLedger.Content(HASH_B, 2)), replacedEntry.historicalHashes());
		assertEquals(OwnershipLedger.Status.TOMBSTONE, removed.ownershipLedger().entries().get("config/example.txt").currentStatus());
		assertEquals(OwnershipLedger.Status.PRESENT, returned.ownershipLedger().entries().get("config/example.txt").currentStatus());
		assertEquals(replacedEntry.historicalHashes(), returned.ownershipLedger().entries().get("config/example.txt").historicalHashes());
		assertEquals(returned.ownershipLedger(), OwnershipLedger.rebuild(List.of(first, replaced, removed, returned)));
	}

	@Test
	void digestUsesCanonicalPathAndContentOrdering() {
		OwnershipLedger.Entry first = entry("config/first.txt", HASH_A, 1);
		OwnershipLedger.Entry second = entry("config/second.txt", HASH_B, 2);
		Map<String, OwnershipLedger.Entry> forward = new LinkedHashMap<>();
		forward.put(first.logicalPath(), first);
		forward.put(second.logicalPath(), second);
		Map<String, OwnershipLedger.Entry> reverse = new LinkedHashMap<>();
		reverse.put(second.logicalPath(), second);
		reverse.put(first.logicalPath(), first);

		assertEquals(new OwnershipLedger("abc1234", forward), new OwnershipLedger("abc1234", reverse));
		assertEquals(OwnershipLedger.digest("abc1234", forward), OwnershipLedger.digest("abc1234", reverse));
	}

	private static GroupManifest manifest(GroupManifest.GroupFile... files) {
		NavigableMap<String, GroupManifest.GroupFile> values = new TreeMap<>();
		if (files != null) for (GroupManifest.GroupFile file : files) values.put("config/example.txt", file);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", true, false, new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), Set.of(), values);
		return new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("main", group)), new TreeMap<>());
	}

	private static GroupManifest.GroupFile file(String hash, long size) {
		return new GroupManifest.GroupFile(size, "config", false, false, false, hash, null);
	}

	private static OwnershipLedger.Entry entry(String path, String hash, long size) {
		return new OwnershipLedger.Entry(path, Set.of(new OwnershipLedger.Content(hash, size)), Set.of("main"), "a".repeat(40), "b".repeat(40), OwnershipLedger.Status.PRESENT);
	}
}
