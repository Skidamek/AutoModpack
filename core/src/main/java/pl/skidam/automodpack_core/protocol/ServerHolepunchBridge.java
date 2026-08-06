package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;

import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.ProtocolPipeline;
import pl.skidam.automodpack_core.protocol.netty.handler.ErrorPrinter;
import pl.skidam.mcholepunch.HolepunchConnection;
import pl.skidam.mcholepunch.HolepunchFailure;
import pl.skidam.mcholepunch.HolepunchHandler;
import pl.skidam.mcholepunch.MinecraftProtocol;
import pl.skidam.mcholepunch.minecraft.HolepunchServerRegistry;

public final class ServerHolepunchBridge {
	private static final int EVENT_LOOP_TICK_MILLIS = 10;
	private static final int MAX_PUMP_PASSES = 1024;
	private static final Set<HolepunchSocket> sockets = ConcurrentHashMap.newKeySet();
	private static ExecutorService executor;
	private static HolepunchServerRegistry.Registration registration;

	private ServerHolepunchBridge() {}

	public static synchronized void register(NettyServer server) {
		if (!serverConfig.modpackHost || serverConfig.connectionMode != ModpackConnectionMode.HOLEPUNCH || registration != null) return;

		ExecutorService bridgeExecutor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "mcholepunch-automodpack-handler");
			thread.setDaemon(true);
			return thread;
		});
		executor = bridgeExecutor;
		try {
			registration = HolepunchServerRegistry.register(
					MinecraftProtocol.forMinecraftVersion(MC_VERSION).loginPacketLayout(),
					bridgeExecutor,
					4L * 1024 * 1024,
					Duration.ofSeconds(10),
					(username, address, marker) -> new HolepunchHandler() {
						private volatile HolepunchSocket socket;

						@Override
						public void onOpen(HolepunchConnection connection) {
							LOGGER.debug("Holepunched AutoModpack connection opened: {}", address);
							HolepunchSocket openedSocket = new HolepunchSocket(connection);
							socket = openedSocket;
							sockets.add(openedSocket);
							try {
								bridgeExecutor.execute(() -> runProtocol(server, openedSocket, address));
							} catch (RuntimeException e) {
								sockets.remove(openedSocket);
								openedSocket.close();
								throw e;
							}
						}

						@Override
						public void onRead(ByteBuffer data) {
							HolepunchSocket openedSocket = socket;
							if (openedSocket == null) return;
							byte[] bytes = new byte[data.remaining()];
							data.get(bytes);
							openedSocket.feedReadData(bytes);
						}

						@Override
						public void onClosed(HolepunchFailure failure) {
							LOGGER.debug("Holepunched AutoModpack connection closed: {} ({})", address, failure);
							HolepunchSocket openedSocket = socket;
							if (openedSocket != null) openedSocket.close();
						}
					});
		} catch (RuntimeException e) {
			bridgeExecutor.shutdownNow();
			executor = null;
			throw e;
		}
	}

	public static synchronized boolean isRegistered() {
		return registration != null;
	}

	public static synchronized void close() {
		if (registration != null) {
			registration.close();
			registration = null;
		}
		for (HolepunchSocket socket : sockets) {
			socket.close();
		}
		sockets.clear();
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	private static void runProtocol(NettyServer server, HolepunchSocket socket, SocketAddress remoteAddress) {
		EmbeddedChannel channel = null;
		try (
				socket;
				DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
				DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
			channel = new EmbeddedChannel();
			channel.attr(NettyServer.REAL_REMOTE_ADDR).set(remoteAddress);
			channel.pipeline().addLast("error-printer-first", new ErrorPrinter());
			if (server.getSslCtx() != null) {
				SslHandler sslHandler = server.getSslCtx().newHandler(channel.alloc());
				sslHandler.handshakeFuture().addListener(future -> {
					if (future.isSuccess()) {
						LOGGER.debug("TLS handshake completed via holepunch: {}", remoteAddress);
					} else {
						LOGGER.debug("TLS handshake failed via holepunch: {}", remoteAddress, future.cause());
					}
				});
				channel.pipeline().addLast("tls", sslHandler);
			} else {
				LOGGER.debug("TLS termination handled externally for holepunch connection: {}", remoteAddress);
			}

			ProtocolPipeline.install(channel, server, remoteAddress);

			socket.setSoTimeout(EVENT_LOOP_TICK_MILLIS);
			byte[] readBuffer = new byte[8192];

			for (;;) {
				try {
					int read = input.read(readBuffer);
					if (read == -1) break;

					ByteBuf inbound = channel.alloc().buffer(read);
					inbound.writeBytes(readBuffer, 0, read);
					channel.writeInbound(inbound);
				} catch (SocketTimeoutException ignored) {
					// The timeout is the event-loop tick while the peer waits for output.
				}

				pumpEmbeddedChannel(channel, output);
			}

			pumpEmbeddedChannel(channel, output);
		} catch (Exception e) {
			LOGGER.debug("AutoModpack holepunch handler ended", e);
		} finally {
			sockets.remove(socket);
			if (channel != null) channel.finishAndReleaseAll();
		}
	}

	static void pumpEmbeddedChannel(EmbeddedChannel channel, DataOutputStream output) throws IOException {
		boolean producedOutput = false;

		for (int pass = 0; pass < MAX_PUMP_PASSES; pass++) {
			channel.runPendingTasks();
			channel.flushOutbound();
			channel.runPendingTasks();
			channel.checkException();

			boolean drained = drainOutbound(channel, output);
			producedOutput |= drained;
			if (!drained) break;
		}

		if (producedOutput) output.flush();
	}

	private static boolean drainOutbound(EmbeddedChannel channel, DataOutputStream output) throws IOException {
		boolean producedOutput = false;
		Object message;

		while ((message = channel.readOutbound()) != null) {
			producedOutput = true;
			try {
				if (!(message instanceof ByteBuf buffer)) {
					throw new IOException("Unexpected outbound message type: " + message.getClass().getName());
				}
				buffer.readBytes(output, buffer.readableBytes());
			} finally {
				ReferenceCountUtil.release(message);
			}
		}

		return producedOutput;
	}
}
