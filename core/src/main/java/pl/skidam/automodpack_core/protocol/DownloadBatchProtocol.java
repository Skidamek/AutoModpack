package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.BATCH_PROTOCOL_VERSION;
import static pl.skidam.automodpack_core.protocol.NetUtils.MIN_CHUNK_SIZE;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Bounds and validation shared by the v2 request codec, client, and server. */
public final class DownloadBatchProtocol {
	public static final byte VERSION = BATCH_PROTOCOL_VERSION;
	public static final byte ITEM_SUCCESS = 0x00;
	public static final byte ITEM_FAILURE = 0x01;
	// The existing minimum compressed-frame payload is 1 MiB; reserve one sixteenth for a control envelope so it never competes with file data.
	public static final int MAX_REQUEST_BYTES = MIN_CHUNK_SIZE / 16;
	public static final int MAX_ERROR_BYTES = MAX_REQUEST_BYTES / 16;
	public static final int MAX_KEY_BYTES = GenerationHistoryIndex.CATALOGUE_REQUEST_PREFIX.length() + HashUtils.SHA1_HEX_LENGTH;
	// This derives the item cap from the envelope and per-item wire fields instead of introducing a second unrelated limit.
	public static final int MAX_ITEM_COUNT = (MAX_REQUEST_BYTES - Byte.BYTES - Byte.BYTES - Secrets.BYTE_LENGTH - Integer.BYTES)
			/ (Integer.BYTES + Integer.BYTES + MAX_KEY_BYTES);

	private DownloadBatchProtocol() {}

	public static void validateItems(List<? extends Item> items) {
		if (items == null) throw new IllegalArgumentException("Batch items are missing");
		if (items.size() > MAX_ITEM_COUNT) throw new IllegalArgumentException("Batch contains too many items: " + items.size());

		Set<Integer> itemIds = new HashSet<>(items.size());
		long requestBytes = Byte.BYTES + Byte.BYTES + Secrets.BYTE_LENGTH + Integer.BYTES;
		for (Item item : items) {
			if (item == null) throw new IllegalArgumentException("Batch item is missing");
			if (item.itemId() <= 0 || !itemIds.add(item.itemId())) throw new IllegalArgumentException("Batch item IDs must be positive and unique");
			if (item.key() == null) throw new IllegalArgumentException("Batch item key is missing");
			byte[] keyBytes = item.key().getBytes(StandardCharsets.UTF_8);
			validateKey(item.key(), keyBytes);
			requestBytes += Integer.BYTES + Integer.BYTES + keyBytes.length;
			if (requestBytes > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Batch request is too large");
		}
	}

	public static void validateKey(String key, byte[] keyBytes) {
		if (key == null || keyBytes == null || keyBytes.length == 0 || keyBytes.length > MAX_KEY_BYTES) throw new IllegalArgumentException("Invalid batch key length");
		boolean objectKey = HashUtils.isCanonicalSha1(key);
		String prefix = GenerationHistoryIndex.CATALOGUE_REQUEST_PREFIX;
		boolean catalogueKey = key.startsWith(prefix) && HashUtils.isCanonicalSha1(key.substring(prefix.length()));
		if ((!objectKey && !catalogueKey) || !key.equals(new String(keyBytes, StandardCharsets.UTF_8))) throw new IllegalArgumentException("Invalid canonical batch key");
	}

	public interface Item {
		int itemId();

		String key();
	}
}
