package pl.skidam.automodpack.networking.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

// credits to fabric api
public class ClientLoginNetworking {

    private static final Map<Identifier, LoginQueryRequestHandler> handlers = new HashMap<>();

    /**
     * Registers a handler to a query request channel.
     * A global receiver is registered to all connections, in the present and future.
     *
     * @param channelName the id of the channel
     * @param handler the handler
     */
    public static void registerGlobalReceiver(Identifier channelName, LoginQueryRequestHandler handler) {
        Objects.requireNonNull(channelName, "Channel name cannot be null");
        Objects.requireNonNull(handler, "Channel handler cannot be null");

        handlers.put(channelName, handler);
    }

    public static LoginQueryRequestHandler getHandler(Identifier channelName) {
        return handlers.get(channelName);
    }

    @FunctionalInterface
    public interface LoginQueryRequestHandler {
        /**
         * Handles an incoming query request from a server.
         *
         * <p>This method is executed on {@linkplain io.netty.channel.EventLoop netty's event loops}.
         * Modification to the game should be {@linkplain net.minecraft.util.thread.ThreadExecutor#submit(Runnable) scheduled} using the provided Minecraft client instance.
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
        CompletableFuture<@Nullable PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf);
    }
}
