package pl.skidam.automodpack_core.protocol.netty.message.request;

import static pl.skidam.automodpack_core.protocol.NetUtils.BATCH_FILE_REQUEST_TYPE;

import java.util.List;

import pl.skidam.automodpack_core.protocol.DownloadBatchProtocol;
import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;

public final class BatchFileRequestMessage extends ProtocolMessage {
	private final List<Item> items;

	public BatchFileRequestMessage(byte version, byte[] secret, List<Item> items) {
		super(version, BATCH_FILE_REQUEST_TYPE, secret);
		DownloadBatchProtocol.validateItems(items);
		this.items = List.copyOf(items);
	}

	public List<Item> getItems() {
		return items;
	}

	public record Item(int itemId, String key) implements DownloadBatchProtocol.Item {
		public Item {
			if (key == null) throw new IllegalArgumentException("Batch item key is missing");
		}
	}
}
