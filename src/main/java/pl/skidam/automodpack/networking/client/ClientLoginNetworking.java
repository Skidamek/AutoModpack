package pl.skidam.automodpack.networking.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

// credits to fabric api
public class ClientLoginNetworking {

    private static final Map<ResourceLocation, LoginQueryRequestHandler> handlers = new HashMap<>();

    /**
     * Registers a handler to a query request channel.
     * A global receiver is registered to all connections, in the present and future.
     *
     * @param channelName the id of the channel
     * @param handler the handler
     */
    public static void registerGlobalReceiver(ResourceLocation channelName, LoginQueryRequestHandler handler) {
        Objects.requireNonNull(channelName, "Channel name cannot be null");
        Objects.requireNonNull(handler, "Channel handler cannot be null");

        handlers.put(channelName, handler);
    }

    public static LoginQueryRequestHandler getHandler(ResourceLocation channelName) {
        return handlers.get(channelName);
    }

    @FunctionalInterface
    public interface LoginQueryRequestHandler {
        /**
         * Handles an incoming query request from a server.
         *
         * <p>This method is executed on {@linkplain io.netty.channel.EventLoop netty's event loops}.
         * Modification to the game should be {@linkplain net.minecraft.util.thread.BlockableEventLoop#submit(Runnable) scheduled} using the provided Minecraft client instance.
         *
         * <p>The return value of this method is a completable future that may be used to delay the login process to the server until a task {@link CompletableFuture#isDone() is done}.
         * The future should complete in reasonably time to prevent disconnection by the server.
         * If your request processes instantly, you may use {@link CompletableFuture#completedFuture(Object)} to wrap your response for immediate sending.
         *
         * @param client the client
         * @param handler the network handler that received this packet
         * @param buf the payload of the packet
         * @return a completable future which contains the payload to respond to the server with.
         * If the future contains {@code null}, then the server will be notified that the client did not understand the query.
         */
        CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf);
    }
}
