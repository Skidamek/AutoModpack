package pl.skidam.automodpack_core.protocol.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/**
 * EmbeddedChannel reports a write complete as soon as the message is moved into the outbound queue, so ChunkedWriteHandler and SslHandler treat the channel as always writable and expand the whole stream on the heap.
 * Writability here means those queued bytes have actually been drained.
 */
public final class BackpressuredEmbeddedChannel extends EmbeddedChannel {
	private final long highWatermark;
	private long queuedOutboundBytes;

	public BackpressuredEmbeddedChannel(long highWatermark) {
		if (highWatermark <= 0) throw new IllegalArgumentException("highWatermark must be positive");
		this.highWatermark = highWatermark;
	}

	@Override
	public boolean isWritable() {
		return queuedOutboundBytes == 0 && super.isWritable();
	}

	@Override
	public long bytesBeforeUnwritable() {
		return queuedOutboundBytes == 0 ? Math.min(highWatermark, super.bytesBeforeUnwritable()) : 0;
	}

	@Override
	public long bytesBeforeWritable() {
		return queuedOutboundBytes == 0 ? 0 : queuedOutboundBytes;
	}

	@Override
	protected void doWrite(ChannelOutboundBuffer in) throws Exception {
		for (;;) {
			Object msg = in.current();
			if (msg == null) break;
			long size = outboundSize(msg);
			if (queuedOutboundBytes >= highWatermark || (queuedOutboundBytes > 0 && queuedOutboundBytes + size > highWatermark)) break;
			ReferenceCountUtil.retain(msg);
			queuedOutboundBytes += size;
			handleOutboundMessage(msg);
			in.remove();
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T readOutbound() {
		T msg = super.readOutbound();
		if (msg != null) {
			queuedOutboundBytes -= outboundSize(msg);
			if (queuedOutboundBytes < 0) queuedOutboundBytes = 0;
		}
		return msg;
	}

	@Override
	public boolean releaseOutbound() {
		queuedOutboundBytes = 0;
		return super.releaseOutbound();
	}

	private static long outboundSize(Object msg) {
		if (msg instanceof ByteBuf buffer) return buffer.readableBytes();
		if (msg instanceof ByteBufHolder holder) return holder.content().readableBytes();
		return 0;
	}
}
