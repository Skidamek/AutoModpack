package pl.skidam.automodpack.networking.client;

import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.Nullable;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.LoginQueryParser;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.resources.Identifier;

import static pl.skidam.automodpack_core.Constants.LOGGER;

// credits to fabric api
public class ClientLoginNetworkAddon {
    private final ClientHandshakePacketListenerImpl handler;
    private final Minecraft client;

    public ClientLoginNetworkAddon(ClientHandshakePacketListenerImpl clientLoginNetworkHandler, Minecraft client) {
        this.handler = clientLoginNetworkHandler;
        this.client = client;
    }

    /**
     * Handles an incoming query request during login.
     *
     * @param packet the packet to handle
     * @return true if the packet was handled
     */
    public boolean handlePacket(ClientboundCustomQueryPacket packet) {
        LoginQueryParser loginQuery = new LoginQueryParser(packet);
        if (loginQuery.success) return handlePacket(loginQuery.queryId, loginQuery.channelName, loginQuery.buf);
        return false;
    }

    private boolean handlePacket(int queryId, Identifier channelName, FriendlyByteBuf payload) {
        @Nullable ClientLoginNetworking.LoginQueryRequestHandler handler = ClientLoginNetworking.getHandler(channelName);

        if (handler == null) {
            return false;
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(payload.slice());

        try {
            CompletableFuture<FriendlyByteBuf> future = handler.receive(this.client, this.handler, buf);
            future.whenComplete((resultBuf, throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Failed to handle login query in channel \"{}\"", channelName, throwable);
                    resultBuf = new FriendlyByteBuf(Unpooled.buffer());
                }
                if (resultBuf == null) {
                    resultBuf = new FriendlyByteBuf(Unpooled.buffer());
                }
                sendResponse(queryId, channelName, resultBuf);
            });
        } catch (Throwable e) {
            LOGGER.error("Encountered exception while handling in channel with name \"{}\"", channelName, e);
            sendResponse(queryId, channelName, new FriendlyByteBuf(Unpooled.buffer()));
            throw e;
        }

        return true;
    }

    private void sendResponse(int queryId, Identifier channelName, FriendlyByteBuf resultBuf) {
        ServerboundCustomQueryAnswerPacket packet = new ServerboundCustomQueryAnswerPacket(queryId, /*? if <1.20.2 {*/ /*resultBuf *//*?} else {*/ new LoginResponsePayload(channelName, resultBuf) /*?}*/);
        ((ClientLoginNetworkHandlerAccessor) this.handler).getConnection().send(packet);
    }
}
