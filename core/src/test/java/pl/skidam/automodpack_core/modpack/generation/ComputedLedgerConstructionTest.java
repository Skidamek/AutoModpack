package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class ComputedLedgerConstructionTest {
	static OwnershipLedger.Entry entry(String path, String hash) {
		var hs = new TreeSet<OwnershipLedger.Content>(Comparator.comparing(OwnershipLedger.Content::sha1).thenComparingLong(OwnershipLedger.Content::size));
		hs.add(new OwnershipLedger.Content(hash.repeat(40), 7));
		return new OwnershipLedger.Entry(path, hs, new TreeSet<>(Set.of("main")), OwnershipLedger.Status.PRESENT);
	}

	static TreeMap<String, OwnershipLedger.Entry> source() {
		var m = new TreeMap<String, OwnershipLedger.Entry>();
		m.put("mods/a.jar", entry("mods/a.jar", "a"));
		m.put("mods/z.jar", entry("mods/z.jar", "b"));
		return m;
	}

	@Test
	void computingConstructorOwnsItsSnapshotAndKeepsNaturalCanonicalOrder() {
		var m = new TreeMap<String, OwnershipLedger.Entry>(Comparator.reverseOrder());
		m.putAll(source());
		var ledger = new OwnershipLedger("abc1234", m);
		assertEquals(OwnershipLedger.digest("abc1234", source()), ledger.digest());
		assertEquals(List.of("mods/a.jar", "mods/z.jar"), new ArrayList<>(ledger.entries().keySet()));
		m.clear();
		assertEquals(2, ledger.entries().size());
		assertThrows(UnsupportedOperationException.class, () -> ledger.entries().clear());
		assertThrows(UnsupportedOperationException.class, () -> ledger.entries().firstEntry().setValue(entry("mods/a.jar", "c")));
	}

	@Test
	void suppliedDigestStillMustMatchAndOwnsItsSnapshot() {
		var m = source();
		String d = OwnershipLedger.digest("abc1234", m);
		var ledger = new OwnershipLedger("abc1234", m, d);
		m.clear();
		assertEquals(2, ledger.entries().size());
		assertEquals(d, ledger.digest());
		assertThrows(IllegalArgumentException.class, () -> new OwnershipLedger("abc1234", source(), null));
		assertThrows(IllegalArgumentException.class, () -> new OwnershipLedger("abc1234", source(), "0".repeat(40)));
	}

	@Test
	void deserializationNeverGetsTheComputingPrivilege() {
		var ledger = new OwnershipLedger("abc1234", source());
		var f = ledger.toFields();
		assertEquals(ledger, OwnershipLedger.fromFields(f));
		f.digest = null;
		assertThrows(IllegalArgumentException.class, () -> OwnershipLedger.fromFields(f));
		f.digest = "0".repeat(40);
		assertThrows(IllegalArgumentException.class, () -> OwnershipLedger.fromFields(f));
	}

	@Test
	void oldDigestCannotValidateChangedContent() {
		var ledger = new OwnershipLedger("abc1234", source());
		var f = ledger.toFields();
		f.entries.get(0).historicalHashes.get(0).size++;
		assertThrows(IllegalArgumentException.class, () -> OwnershipLedger.fromFields(f));
	}

	@Test
	void keyConsistencyAndModpackValidationRemainMandatory() {
		var m = source();
		m.put("mods/wrong.jar", entry("mods/a.jar", "a"));
		assertThrows(IllegalArgumentException.class, () -> new OwnershipLedger("abc1234", m));
		assertThrows(IllegalArgumentException.class, () -> new OwnershipLedger("bad!", source()));
	}

	@Test
	void nullInputStillMeansEmptyOnlyForTheComputingConstructor() {
		assertEquals(OwnershipLedger.empty("abc1234"), new OwnershipLedger("abc1234", (Map<String, OwnershipLedger.Entry>) null));
		assertThrows(IllegalArgumentException.class, () -> new OwnershipLedger("abc1234", null, null));
	}

	@Test
	void computingAndWireValidationKeepThePublishedCanonicalDigest() {
		var m = new TreeMap<String, OwnershipLedger.Entry>();
		for (int i = 0; i < 300; i++) {
			String path = "mods/file-" + i + ".jar";
			m.put(path, entry(path, "a"));
		}
		var ledger = new OwnershipLedger("abc1234", m);
		assertEquals("419f98b7903e297bd4c695acdc8453a7cc550ea9", ledger.digest());
		assertEquals(ledger, OwnershipLedger.fromFields(ledger.toFields()));
	}
}
