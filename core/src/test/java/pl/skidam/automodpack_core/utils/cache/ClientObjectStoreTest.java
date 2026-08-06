package pl.skidam.automodpack_core.utils.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ClientObjectStoreTest {
	@Test
	void normalizesObjectHashes() {
		assertEquals("0123456789abcdef0123456789abcdef01234567", ClientObjectStore.normalizeHash("0123456789ABCDEF0123456789ABCDEF01234567"));
		assertThrows(IllegalArgumentException.class, () -> ClientObjectStore.normalizeHash("not-a-sha1"));
	}
}
