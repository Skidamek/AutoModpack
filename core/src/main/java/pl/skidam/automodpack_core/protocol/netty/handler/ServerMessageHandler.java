package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.EchoMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.FileRequestMessage;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Serves post-handshake protocol messages. File sends are {@link FileSend}. */
public class ServerMessageHandler extends SimpleChannelInboundHandler<ProtocolMessage> {

	private final NettyServer server;
	private final FileSend fileSend;
	private String authenticatedSecret;
	private byte protocolVersion;
	private int chunkSize;

	public ServerMessageHandler(NettyServer server) {
		this.server = server;
		this.fileSend = new FileSend(server);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		server.removeConnection(ctx.channel());
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, ProtocolMessage msg) throws Exception {
		this.protocolVersion = ctx.pipeline().channel().attr(NettyServer.PROTOCOL_VERSION).get();
		this.chunkSize = ctx.pipeline().channel().attr(NettyServer.CHUNK_SIZE).get();

		byte clientProtocolVersion = msg.getVersion();

		if (protocolVersion != clientProtocolVersion) {
			FileSend.sendError(ctx, protocolVersion, "Protocol version mismatch");
			return;
		}

		SocketAddress address = ctx.channel().attr(NettyServer.REAL_REMOTE_ADDR).get();

		// Validate the secret; rejection reasons are logged by the auth layer
		if (!validateSecret(ctx, address, msg.getSecret())) {
			FileSend.sendError(ctx, protocolVersion, "Authentication failed");
			return;
		}

		switch (msg.getType()) {
			case ECHO_TYPE :
				EchoMessage echoMsg = (EchoMessage) msg;
				ByteBuf echoBuf = ctx.alloc().buffer(1 + 1 + msg.getSecret().length + echoMsg.getData().length);
				echoBuf.writeByte(protocolVersion);
				echoBuf.writeByte(ECHO_TYPE);
				echoBuf.writeBytes(echoMsg.getSecret());
				echoBuf.writeBytes(echoMsg.getData());
				FileSend.writeControlAndFlush(ctx, echoBuf).addListener(ChannelFutureListener.CLOSE);
				break;
			case FILE_REQUEST_TYPE :
				FileRequestMessage fileRequest = (FileRequestMessage) msg;
				if (documentUnchanged(fileRequest)) {
					FileSend.sendUnchanged(ctx, protocolVersion);
					break;
				}
				fileSend.send(ctx, fileRequest.getFileHash(), protocolVersion, chunkSize, fileRequest.getOffset(), fileRequest.getEndInclusive());
				break;
			default :
				FileSend.sendError(ctx, protocolVersion, "Unknown message type");
		}
	}

	private boolean documentUnchanged(FileRequestMessage request) {
		byte[] expected = request.getExpectedSha1();
		if (expected == null) return false;
		String key = new String(request.getFileHash(), StandardCharsets.UTF_8);
		// The expected-hash comparison happens here, after authentication; the decoder only parses the field.
		if (!key.equals(GenerationHosting.HEAD_DOCUMENT_KEY) && !key.equals(GenerationHosting.JOURNAL_KEY)) return false;
		Optional<Path> path = server.getPath(key);
		if (path.isEmpty()) return false;
		String servedHash = HashUtils.getHash(path.get());
		String expectedHash = new String(expected, StandardCharsets.UTF_8);
		// The expected hash is untrusted wire input: only a well-formed digest matching the served document's SHA-1 short-circuits, anything else falls through to the normal send.
		return servedHash != null && HashUtils.isSha1(expectedHash) && servedHash.equals(HashUtils.normalizeSha1(expectedHash));
	}

	private boolean validateSecret(ChannelHandlerContext ctx, SocketAddress address, byte[] secret) {
		String decodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
		if (!Secrets.isSecretValid(decodedSecret, address)) return false;
		if (authenticatedSecret == null) {
			authenticatedSecret = decodedSecret;
			server.addConnection(ctx.channel(), decodedSecret);
			ConnectionLifetimeHandler lifetime = ctx.pipeline().get(ConnectionLifetimeHandler.class);
			if (lifetime != null) lifetime.authenticated(ctx);
		}
		if (!authenticatedSecret.equals(decodedSecret)) {
			LOGGER.warn("Connection from {} tried to switch to a different secret", address);
			return false;
		}
		return true;
	}
}
