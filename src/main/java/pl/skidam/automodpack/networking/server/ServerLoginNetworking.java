package pl.skidam.automodpack.networking.server;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import pl.skidam.automodpack.networking.PacketSender;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;

// credits to fabric api
public class ServerLoginNetworking {

    private static final Map<ResourceLocation, LoginQueryResponseHandler> handlers = new HashMap<>();

    /**
     * Registers a handler to a query response channel.
     * A global receiver is registered to all connections, in the present and future.
     *
     * @param channelName the id of the channel
     * @param handler the handler
     */
    public static void registerGlobalReceiver(ResourceLocation channelName, LoginQueryResponseHandler handler) {
        Objects.requireNonNull(channelName, "Channel name cannot be null");
        Objects.requireNonNull(handler, "Channel handler cannot be null");

        handlers.put(channelName, handler);
    }

    public static LoginQueryResponseHandler getHandler(ResourceLocation channelName) {
        return handlers.get(channelName);
    }

    @FunctionalInterface
    public interface LoginQueryResponseHandler {
        /**
         * Handles an incoming query response from a client.
         *
         * <p>This method is executed on {@linkplain io.netty.channel.EventLoop netty's event loops}.
         * Modification to the game should be {@linkplain net.minecraft.util.thread.BlockableEventLoop#submit(Runnable) scheduled} using the provided Minecraft client instance.
         *
         * <p><b>Whether the client understood the query should be checked before reading from the payload of the packet.</b>
         * @param server the server
         * @param handler the network handler that received this packet, representing the player/client who sent the response
         * @param understood whether the client understood the packet
         * @param buf the payload of the packet
         * @param synchronizer the synchronizer which may be used to delay log-in till a {@link Future} is completed.
         */
        void receive(MinecraftServer server, ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf, LoginSynchronizer synchronizer, PacketSender responseSender);
    }


    /**
     * Allows blocking client log-in until all futures passed into {@link LoginSynchronizer#waitFor(Future)} are completed.
     *
     * @apiNote this interface is not intended to be implemented by users of api.
     */
    @FunctionalInterface
    public interface LoginSynchronizer {
        /**
         * Allows blocking client log-in until the {@code future} is {@link Future#isDone() done}.
         *
         * <p>Since packet reception happens on netty's event loops, this allows handlers to
         * perform logic on the Server Thread, etc. For instance, a handler can prepare an
         * upcoming query request or check necessary login data on the server thread.</p>
         *
         * <p>Here is an example where the player log-in is blocked so that a credential check and
         * building of a followup query request can be performed properly on the logical server
         * thread before the player successfully logs in:
         * <pre>{@code
         * ServerLoginNetworking.registerGlobalReceiver(CHECK_CHANNEL, (server, handler, understood, buf, synchronizer, responseSender) -&gt; {
         * 	if (!understood) {
         * 		handler.disconnect(Text.literal("Only accept clients that can check!"));
         * 		return;
         * 	}
         *
         * 	String checkMessage = buf.readString(Short.MAX_VALUE);
         *
         * 	// Just send the CompletableFuture returned by the server's submit method
         * 	synchronizer.waitFor(server.submit(() -&gt; {
         * 		LoginInfoChecker checker = LoginInfoChecker.get(server);
         *
         * 		if (!checker.check(handler.getConnectionInfo(), checkMessage)) {
         * 			handler.disconnect(Text.literal("Invalid credentials!"));
         * 			return;
         * 		}
         *
         * 		responseSender.send(UPCOMING_CHECK, checker.buildSecondQueryPacket(handler, checkMessage));
         * 	}));
         * });
         * }</pre>
         * Usually it is enough to pass the return value for {@link net.minecraft.util.thread.BlockableEventLoop#submit(Runnable)} for {@code future}.</p>
         *
         * @param future the future that must be done before the player can log in
         */
        void waitFor(Future<?> future);
    }

}
