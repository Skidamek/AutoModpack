package pl.skidam.automodpack.networking.server;

import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import pl.skidam.automodpack.mixin.core.ServerLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.LoginNetworkingIDs;
import pl.skidam.automodpack.networking.LoginQueryParser;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.PacketSender;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

// credits to fabric api
public class ServerLoginNetworkAddon implements PacketSender {

    private final ServerLoginPacketListenerImpl handler;
    private final Connection connection;
    private final MinecraftServer server;
    private final Collection<Future<?>> synchronizers = new ConcurrentLinkedQueue<>();
    public final Map<Integer, ResourceLocation> channels = new ConcurrentHashMap<>();
    private boolean firstTick = true;

    public ServerLoginNetworkAddon(ServerLoginPacketListenerImpl serverLoginNetworkHandler) {
        this.handler = serverLoginNetworkHandler;
        this.connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
        this.server = ((ServerLoginNetworkHandlerAccessor) handler).getServer();
    }

    // returns true if we should move to another login stage
    public boolean queryTick() {
        if (this.firstTick) {

            // Fire onReady event
            ModPackets.onReady(this.handler, this.server, this.synchronizers::add, this);

            this.firstTick = false;
        }

        this.synchronizers.removeIf(future -> {
            if (!future.isDone()) {
                return false;
            }

            try {
                future.get();
            } catch (Exception ignored) {
            }

            return true;
        });

        return this.channels.isEmpty() && this.synchronizers.isEmpty();
    }

    /**
     * Handles an incoming query response during login.
     *
     * @param packet the packet to handle
     * @return true if the packet was handled
     */
    public boolean handle(ServerboundCustomQueryAnswerPacket packet) {
        LoginQueryParser loginQuery = new LoginQueryParser(packet);
        if (loginQuery.success) return handle(loginQuery.queryId, loginQuery.buf);
        return false;
    }

    private boolean handle(int queryId, @Nullable FriendlyByteBuf originalBuf) {

        ResourceLocation channel = this.channels.remove(queryId);

        if (channel == null) {
            // Not an AutoModpack packet.
            return false;
        }

        boolean understood = originalBuf != null;
        @Nullable ServerLoginNetworking.LoginQueryResponseHandler handler = ServerLoginNetworking.getHandler(channel);

        if (handler == null) {
            return false;
        }

        FriendlyByteBuf buf = understood ? new FriendlyByteBuf(originalBuf.slice()) : new FriendlyByteBuf(Unpooled.EMPTY_BUFFER);

        try {
            handler.receive(this.server, this.handler, understood, buf, this.synchronizers::add, this);
        } catch (Throwable e) {
            LOGGER.error("Encountered exception while handling in channel \"{}\"", channel, e);
            throw e;
        }

        return true;
    }

    @Override
    public ClientboundCustomQueryPacket createPacket(ResourceLocation channelName, FriendlyByteBuf buf) {
        Integer queryId = LoginNetworkingIDs.getByKey(channelName);

        if (queryId == null) {
            return null;
        }

        return new ClientboundCustomQueryPacket(queryId, /*? if <1.20.2 {*/ /*channelName, buf *//*?} else {*/ new LoginRequestPayload(channelName, buf) /*?}*/);
    }

    @Override
    public void sendPacket(ClientboundCustomQueryPacket packet) {
        Objects.requireNonNull(packet, "Connection cannot be null");

        LoginQueryParser loginQuery = new LoginQueryParser(packet);
        if (loginQuery.success) {
            this.channels.put(loginQuery.queryId, loginQuery.channelName);
            this.connection.send(packet);
        } else {
            LOGGER.error("Failed to send packet: {}", packet);
        }
    }
}
