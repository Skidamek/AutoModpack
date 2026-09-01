package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.MC_VERSION;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.util.Optional;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import pl.skidam.mcholepunch.MinecraftProtocol;
import pl.skidam.mcholepunch.internal.DebugLog;
import pl.skidam.mcholepunch.internal.protocol.HolepunchMarker;
import pl.skidam.mcholepunch.internal.protocol.LoginCodec;
import pl.skidam.mcholepunch.internal.protocol.VarInts;
import pl.skidam.mcholepunch.server.HolepunchServerRegistry;
import pl.skidam.mcholepunch.server.netty.NettyLoginNegotiator;
import pl.skidam.mcholepunch.server.netty.NettyTakeoverSpec;

/**
 * Claims connections whose Login Start carries a holepunch marker before vanilla builds a login
 * listener for them, so vanilla never owns (ticks, disconnects, or logs about) a holepunch
 * connection. Everything that is not a holepunch login is replayed untouched and this handler
 * removes itself.
 */
public class HolepunchHandshakeHandler extends ChannelInboundHandlerAdapter {
	private static final int LOGIN_INTENTION = 2;
	private static final int MAX_HOSTNAME_BYTES = 255;

	private final KeyPair keyPair;
	private final MinecraftProtocol protocol = MinecraftProtocol.forMinecraftVersion(MC_VERSION);
	private ByteBuf handshake;

	public HolepunchHandshakeHandler(KeyPair keyPair) {
		this.keyPair = keyPair;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof ByteBuf frame)) {
			forwardAndRemove(ctx, msg);
			return;
		}

		if (handshake == null) {
			if (!isLoginIntention(frame.nioBuffer())) {
				forwardAndRemove(ctx, frame);
				return;
			}
			handshake = frame;
			return;
		}

		HolepunchServerRegistry.Registration registration = HolepunchServerRegistry.current();
		LoginCodec.LoginStart loginStart = readLoginStart(frame);
		Optional<HolepunchMarker> marker = loginStart == null ? Optional.empty() : markerOf(loginStart);
		if (registration == null || marker.isEmpty()) {
			replayAndRemove(ctx, frame);
			return;
		}

		ReferenceCountUtil.release(frame);
		releaseHeld();
		ctx.pipeline().remove(this);
		DebugLog.log("login takeover", 0, "marker accepted", "name", loginStart.username(), "remote", ctx.channel().remoteAddress());
		new NettyLoginNegotiator(ctx.channel(), keyPair, loginStart.username(), ctx.channel().remoteAddress(), marker.get(), registration, NettyTakeoverSpec.minecraftLogin()).start();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		if (handshake != null) {
			// No vanilla listener exists yet to turn this into a disconnect, and handing a reason to
			// a listener-less Connection would crash its teardown; drop the connection quietly.
			ctx.close();
			return;
		}
		super.exceptionCaught(ctx, cause);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		releaseHeld();
		super.channelInactive(ctx);
	}

	private void forwardAndRemove(ChannelHandlerContext ctx, Object msg) {
		ByteBuf held = handshake;
		handshake = null;
		if (held != null) ctx.fireChannelRead(held);
		ctx.fireChannelRead(msg);
		ctx.pipeline().remove(this);
	}

	private void replayAndRemove(ChannelHandlerContext ctx, ByteBuf loginStartFrame) {
		ByteBuf held = handshake;
		handshake = null;
		ctx.fireChannelRead(held);
		ctx.fireChannelRead(loginStartFrame);
		ctx.pipeline().remove(this);
	}

	private void releaseHeld() {
		if (handshake != null) {
			ReferenceCountUtil.release(handshake);
			handshake = null;
		}
	}

	private LoginCodec.LoginStart readLoginStart(ByteBuf frame) {
		try {
			return LoginCodec.readLoginStart(frame.nioBuffer(), protocol);
		} catch (Exception exception) {
			return null;
		}
	}

	private static Optional<HolepunchMarker> markerOf(LoginCodec.LoginStart loginStart) {
		return HolepunchMarker.decode(loginStart.profileId());
	}

	private boolean isLoginIntention(ByteBuffer frame) {
		try {
			if (VarInts.read(frame) != protocol.handshakePacketId()) return false;
			VarInts.read(frame);
			int hostnameLength = VarInts.read(frame);
			if (hostnameLength < 0 || hostnameLength > MAX_HOSTNAME_BYTES || hostnameLength > frame.remaining()) return false;
			frame.position(frame.position() + hostnameLength);
			if (frame.remaining() < 2) return false;
			frame.position(frame.position() + 2);
			return VarInts.read(frame) == LOGIN_INTENTION && !frame.hasRemaining();
		} catch (Exception exception) {
			return false;
		}
	}
}
