package pl.skidam.automodpack.networking.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.LoginQueryParser;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

// credits to fabric api
public class ClientLoginNetworkAddon {
    private final ClientLoginNetworkHandler handler;
    private final MinecraftClient client;

    public ClientLoginNetworkAddon(ClientLoginNetworkHandler clientLoginNetworkHandler, MinecraftClient client) {
        this.handler = clientLoginNetworkHandler;
        this.client = client;
    }

    /**
     * Handles an incoming query request during login.
     *
     * @param packet the packet to handle
     * @return true if the packet was handled
     */
    public boolean handlePacket(LoginQueryRequestS2CPacket packet) {
        LoginQueryParser loginQuery = new LoginQueryParser(packet);
        if (loginQuery.success) return handlePacket(loginQuery.queryId, loginQuery.channelName, loginQuery.buf);
        return false;
    }

    private boolean handlePacket(int queryId, Identifier channelName, PacketByteBuf payload) {
        @Nullable ClientLoginNetworking.LoginQueryRequestHandler handler = ClientLoginNetworking.getHandler(channelName);

        if (handler == null) {
            return false;
        }

        PacketByteBuf buf = new PacketByteBuf(payload.slice());

        try {
            CompletableFuture<@Nullable PacketByteBuf> future = handler.receive(this.client, this.handler, buf);
            future.thenAccept(resultBuf -> {
                LoginQueryResponseC2SPacket packet = new LoginQueryResponseC2SPacket(queryId, /*? if <1.20.2 {*/ /*resultBuf *//*?} else {*/ new LoginResponsePayload(channelName, resultBuf) /*?}*/);
                ((ClientLoginNetworkHandlerAccessor) this.handler).getConnection().send(packet);
            });
        } catch (Throwable e) {
            LOGGER.error("Encountered exception while handling in channel with name \"{}\"", channelName, e);
            throw e;
        }

        return true;
    }
}
