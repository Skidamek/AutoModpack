package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.skidam.automodpack_core.protocol.NetUtils.BATCH_FILE_REQUEST_TYPE;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.DownloadBatchProtocol;
import pl.skidam.automodpack_core.protocol.netty.message.request.BatchFileRequestMessage;

class ProtocolMessageDecoderTest {
	private static final String OBJECT_KEY = "0123456789abcdef0123456789abcdef01234567";
	private static final String CATALOGUE_KEY = "catalogue/abcdef0123456789abcdef0123456789abcdef01";

	@Test
	void decodesACompleteBatchAndWaitsForAFragmentedKey() {
		ByteBuf encoded = encodeBatch();
		EmbeddedChannel channel = new EmbeddedChannel(new ProtocolMessageDecoder());
		try {
			int split = encoded.writerIndex() - 3;
			assertFalse(channel.writeInbound(encoded.readRetainedSlice(split)));
			assertNull(channel.readInbound());
			assertTrue(channel.writeInbound(encoded.readRetainedSlice(encoded.readableBytes())));
			BatchFileRequestMessage message = channel.readInbound();
			assertEquals(2, message.getItems().size());
			assertEquals(7, message.getItems().get(0).itemId());
			assertEquals(OBJECT_KEY, message.getItems().get(0).key());
			assertEquals(CATALOGUE_KEY, message.getItems().get(1).key());
		} finally {
			encoded.release();
			channel.finishAndReleaseAll();
		}
	}

	private static ByteBuf encodeBatch() {
		byte[] secret = new byte[Secrets.BYTE_LENGTH];
		byte[] first = OBJECT_KEY.getBytes(StandardCharsets.UTF_8);
		byte[] second = CATALOGUE_KEY.getBytes(StandardCharsets.UTF_8);
		return Unpooled.buffer().writeByte(DownloadBatchProtocol.VERSION).writeByte(BATCH_FILE_REQUEST_TYPE).writeBytes(secret).writeInt(2)
				.writeInt(7).writeInt(first.length).writeBytes(first).writeInt(8).writeInt(second.length).writeBytes(second);
	}
}
