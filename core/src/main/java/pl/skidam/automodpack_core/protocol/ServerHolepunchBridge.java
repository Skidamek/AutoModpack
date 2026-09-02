package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;

import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.BackpressuredEmbeddedChannel;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.ProtocolPipeline;
import pl.skidam.automodpack_core.protocol.netty.handler.ErrorPrinter;
import pl.skidam.mcholepunch.HolepunchConnection;
import pl.skidam.mcholepunch.HolepunchFailure;
import pl.skidam.mcholepunch.HolepunchHandler;
import pl.skidam.mcholepunch.server.HolepunchServerRegistry;

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
					bridgeExecutor,
					maxPendingWriteBytes(),
					NETWORK_TIMEOUT,
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
							openedSocket.feedPlainReadData(bytes);
						}

						@Override
						public void onRawRead(ByteBuffer data) {
							HolepunchSocket openedSocket = socket;
							if (openedSocket == null) return;
							byte[] bytes = new byte[data.remaining()];
							data.get(bytes);
							openedSocket.feedCamouflagedReadData(bytes);
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
				DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
			channel = new BackpressuredEmbeddedChannel(maxPendingWriteBytes());
			AtomicBoolean tlsHandshakeComplete = new AtomicBoolean(server.getSslCtx() == null);
			AtomicBoolean transportUpgradeStarted = new AtomicBoolean(server.getSslCtx() == null);
			AtomicBoolean transportUpgradeInProgress = new AtomicBoolean(false);
			channel.attr(NettyServer.REAL_REMOTE_ADDR).set(remoteAddress);
			channel.pipeline().addLast("error-printer-first", new ErrorPrinter());
			SslHandler sslHandler = server.getSslCtx() == null ? null : server.getSslCtx().newHandler(channel.alloc());
			if (sslHandler != null) {
				sslHandler.handshakeFuture().addListener(future -> {
					if (future.isSuccess()) {
						LOGGER.debug("TLS handshake completed via holepunch: {}", remoteAddress);
						tlsHandshakeComplete.set(true);
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

				// Hold produced output while the transport upgrade is in flight: bytes written in
				// that window must not enter the pre-raw stream queue, they have to be submitted
				// after the raw switch so HolepunchSocket camouflages them and the peer decodes
				// them from onRawRead. BackpressuredEmbeddedChannel keeps the undrained window to one pending frame.
				if (transportUpgradeInProgress.get()) continue;
				pumpEmbeddedChannel(channel, socket);
				if (tlsHandshakeComplete.get() && transportUpgradeStarted.compareAndSet(false, true)) {
					transportUpgradeInProgress.set(true);
					startTlsTransportUpgrade(socket, sslHandler, remoteAddress, transportUpgradeInProgress);
				}
			}

			pumpEmbeddedChannel(channel, socket);
		} catch (Exception e) {
			LOGGER.debug("AutoModpack holepunch handler ended", e);
		} finally {
			sockets.remove(socket);
			if (channel != null) channel.finishAndReleaseAll();
		}
	}

	private static void startTlsTransportUpgrade(HolepunchSocket socket, SslHandler sslHandler, SocketAddress remoteAddress, AtomicBoolean inProgress) {
		socket.prepareTransportUpgrade().thenRun(() -> {
			try {
				socket.enableTlsTrafficCamouflage(sslHandler.engine().getSession(), false);
			} catch (Exception exception) {
				throw new CompletionException("Failed to enable TLS record camouflage", exception);
			}
		}).thenCompose(ignored -> socket.commitTransportUpgrade()).exceptionally(error -> {
			LOGGER.debug("TLS record camouflage setup failed via holepunch: {}", remoteAddress, error);
			socket.close();
			return null;
		}).whenComplete((ignored, error) -> inProgress.set(false));
	}

	private static long maxPendingWriteBytes() {
		long maxCompressedFrameLength = 0;
		for (CompressionType type : CompressionType.values()) {
			if (CompressionFactory.isAvailable(type)) {
				CompressionCodec codec = CompressionFactory.createCodec(type);
				maxCompressedFrameLength = Math.max(maxCompressedFrameLength, codec.maxCompressedLength(MAX_CHUNK_SIZE));
			}
		}
		return maxCompressedFrameLength + ProtocolFrameCodec.HEADER_BYTES;
	}

	static void pumpEmbeddedChannel(EmbeddedChannel channel, DataOutputStream output) throws IOException {
		pumpEmbeddedChannel(channel, new OutboundSink() {
			@Override
			public void write(ByteBuf buffer) throws IOException {
				buffer.readBytes(output, buffer.readableBytes());
			}

			@Override
			public void flush() throws IOException {
				output.flush();
			}
		});
	}

	static void pumpEmbeddedChannel(EmbeddedChannel channel, HolepunchSocket socket) throws IOException {
		pumpEmbeddedChannel(channel, new OutboundSink() {
			@Override
			public void write(ByteBuf buffer) throws IOException {
				socket.writeBuffer(buffer);
			}
		});
	}

	private static void pumpEmbeddedChannel(EmbeddedChannel channel, OutboundSink sink) throws IOException {
		boolean producedOutput = false;

		for (int pass = 0; pass < MAX_PUMP_PASSES; pass++) {
			channel.runPendingTasks();
			channel.flushOutbound();
			channel.runPendingTasks();
			channel.checkException();

			boolean drained = drainOutbound(channel, sink);
			producedOutput |= drained;
			if (!drained) break;
		}

		if (producedOutput) sink.flush();
	}

	private static boolean drainOutbound(EmbeddedChannel channel, OutboundSink sink) throws IOException {
		boolean producedOutput = false;
		Object message;

		while ((message = channel.readOutbound()) != null) {
			producedOutput = true;
			try {
				if (!(message instanceof ByteBuf buffer)) {
					throw new IOException("Unexpected outbound message type: " + message.getClass().getName());
				}
				sink.write(buffer);
			} finally {
				ReferenceCountUtil.release(message);
			}
		}

		return producedOutput;
	}

	@FunctionalInterface
	private interface OutboundSink {
		void write(ByteBuf buffer) throws IOException;

		default void flush() throws IOException {}
	}
}
