package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class DownloadBatchProtocolTest {
	private static final String OBJECT_KEY = "0123456789abcdef0123456789abcdef01234567";
	private static final String CATALOGUE_KEY = "catalogue/abcdef0123456789abcdef0123456789abcdef01";

	@Test
	void acceptsEmptyAndMaximumEncodedBatches() {
		assertDoesNotThrow(() -> DownloadBatchProtocol.validateItems(List.of()));
		List<DownloadRequest> requests = new ArrayList<>(DownloadBatchProtocol.MAX_ITEM_COUNT);
		for (int itemId = 1; itemId <= DownloadBatchProtocol.MAX_ITEM_COUNT; itemId++) {
			requests.add(new DownloadRequest(itemId, CATALOGUE_KEY, Path.of("destination-" + itemId), 0, null));
		}
		assertDoesNotThrow(() -> DownloadBatchProtocol.validateItems(requests));
	}

	@Test
	void rejectsDuplicateIdsNonCanonicalKeysAndAnOverlargeBatch() {
		DownloadRequest first = new DownloadRequest(1, OBJECT_KEY, Path.of("one"), 0, null);
		DownloadRequest duplicate = new DownloadRequest(1, CATALOGUE_KEY, Path.of("two"), 0, null);
		assertThrows(IllegalArgumentException.class, () -> DownloadBatchProtocol.validateItems(List.of(first, duplicate)));

		DownloadRequest uppercase = new DownloadRequest(2, OBJECT_KEY.toUpperCase(Locale.ROOT), Path.of("uppercase"), 0, null);
		assertThrows(IllegalArgumentException.class, () -> DownloadBatchProtocol.validateItems(List.of(uppercase)));

		List<DownloadRequest> tooMany = new ArrayList<>(DownloadBatchProtocol.MAX_ITEM_COUNT + 1);
		for (int itemId = 1; itemId <= DownloadBatchProtocol.MAX_ITEM_COUNT + 1; itemId++) {
			tooMany.add(new DownloadRequest(itemId, OBJECT_KEY, Path.of("destination-" + itemId), 0, null));
		}
		assertThrows(IllegalArgumentException.class, () -> DownloadBatchProtocol.validateItems(tooMany));
	}
}
