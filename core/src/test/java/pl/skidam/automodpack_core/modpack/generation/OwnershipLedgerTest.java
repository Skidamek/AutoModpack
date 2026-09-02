package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;

class OwnershipLedgerTest {
	private static final String HASH_A = "1111111111111111111111111111111111111111";
	private static final String HASH_B = "2222222222222222222222222222222222222222";
	private static final String HASH_C = "3333333333333333333333333333333333333333";

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
	void materializesMutuallyExclusiveVariantsTogetherWhileSelectedTargetKeepsOne() {
		GroupManifest manifest = variants("first", HASH_A, 1, "second", HASH_B, 2);
		GenerationRecord record = GenerationRecord.create(manifest, null, Instant.parse("2026-01-05T00:00:00Z"), "");

		OwnershipLedger.Entry entry = record.ownershipLedger().entries().get("config/example.txt");
		assertEquals(Set.of(new OwnershipLedger.Content(HASH_A, 1), new OwnershipLedger.Content(HASH_B, 2)), entry.historicalHashes());
		assertEquals(Set.of("first", "second"), entry.historicalGroupIds());

		SelectedModpackTarget selected = SelectedModpackTarget.prepare(record.toFields(), null, new SelectionIntent(Set.of("second")), ClientPlatform.LINUX);
		assertEquals(Set.of("second"), selected.flatTarget().selectedGroups);
		assertEquals(HASH_B, selected.flatTarget().list.iterator().next().sha1);
	}

	@Test
	void retainsVariantHistoryAcrossTransitionAndRemoval() {
		GenerationRecord initial = GenerationRecord.create(variants("first", HASH_A, 1, "second", HASH_B, 2), null,
				Instant.parse("2026-01-06T00:00:00Z"), "");
		GenerationRecord transitioned = GenerationRecord.create(variants("second", HASH_B, 2, "third", HASH_C, 3), initial,
				Instant.parse("2026-01-07T00:00:00Z"), "");

		OwnershipLedger.Entry transitionedEntry = transitioned.ownershipLedger().entries().get("config/example.txt");
		assertEquals(Set.of(new OwnershipLedger.Content(HASH_A, 1), new OwnershipLedger.Content(HASH_B, 2), new OwnershipLedger.Content(HASH_C, 3)),
				transitionedEntry.historicalHashes());
		assertEquals(Set.of("first", "second", "third"), transitionedEntry.historicalGroupIds());
		OwnershipDelta.Change change = OwnershipDelta.between(initial.ownershipLedger(), transitioned.manifest()).changes().get("config/example.txt");
		assertEquals(OwnershipDelta.Kind.REPLACED, change.kind());
		assertEquals(Set.of(new OwnershipLedger.Content(HASH_B, 2), new OwnershipLedger.Content(HASH_C, 3)), change.contents());
		assertEquals(new OwnershipLedger.Content(HASH_B, 2), change.content());

		GenerationRecord removed = GenerationRecord.create(emptyManifest(), transitioned, Instant.parse("2026-01-08T00:00:00Z"), "");
		OwnershipLedger.Entry removedEntry = removed.ownershipLedger().entries().get("config/example.txt");
		assertEquals(OwnershipLedger.Status.TOMBSTONE, removedEntry.currentStatus());
		assertEquals(transitionedEntry.historicalHashes(), removedEntry.historicalHashes());
		assertEquals(transitionedEntry.historicalGroupIds(), removedEntry.historicalGroupIds());
	}

	@Test
	void ownershipDeltaRoundTripsWithItsCanonicalDigest() {
		GenerationRecord first = GenerationRecord.create(manifest(file(HASH_A, 1)), null, Instant.parse("2026-01-01T00:00:00Z"), "");
		OwnershipDelta delta = OwnershipDelta.between(first.ownershipLedger(), manifest(file(HASH_B, 2)));

		assertEquals(delta, OwnershipDelta.fromFields(delta.toFields()));
		assertEquals(delta.digest(), OwnershipDelta.digest(delta.modpackId(), delta.changes()));
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

	private static GroupManifest variants(String firstGroup, String firstHash, long firstSize, String secondGroup, String secondHash, long secondSize) {
		GroupManifest.Group first = group(Set.of(secondGroup), file(firstHash, firstSize));
		GroupManifest.Group second = group(Set.of(firstGroup), file(secondHash, secondSize));
		GroupManifest raw = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of(firstGroup, first, secondGroup, second)));
		return GroupManifestValidator.validate(raw.toFields());
	}

	private static GroupManifest emptyManifest() {
		return GroupManifestValidator.validate(manifest().toFields());
	}

	private static GroupManifest.Group group(Set<String> breaksWith, GroupManifest.GroupFile file) {
		return new GroupManifest.Group("", "", "", false, false, new TreeSet<>(breaksWith), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("config/example.txt", file)));
	}

	private static GroupManifest manifest(GroupManifest.GroupFile... files) {
		NavigableMap<String, GroupManifest.GroupFile> values = new TreeMap<>();
		if (files != null) for (GroupManifest.GroupFile file : files) values.put("config/example.txt", file);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", true, false, new TreeSet<>(), new TreeSet<>(), Set.of(), values);
		return new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("main", group)));
	}

	private static GroupManifest.GroupFile file(String hash, long size) {
		return new GroupManifest.GroupFile(size, "config", false, hash, null);
	}

	private static OwnershipLedger.Entry entry(String path, String hash, long size) {
		return new OwnershipLedger.Entry(path, Set.of(new OwnershipLedger.Content(hash, size)), Set.of("main"), "a".repeat(40), "b".repeat(40), OwnershipLedger.Status.PRESENT);
	}
}
