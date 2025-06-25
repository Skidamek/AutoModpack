package pl.skidam.automodpack.networking.packet;

import io.netty.buffer.Unpooled;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack_loader_core.SelfUpdater;
import pl.skidam.automodpack_loader_core.platforms.ModrinthAPI;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_loader_core.SelfUpdater.validUpdate;

public class HandshakeC2SPacket {

    public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
        try {
            String serverResponse = buf.readUtf(Short.MAX_VALUE);

            HandshakePacket serverHandshakePacket = HandshakePacket.fromJson(serverResponse);

            String loader = LOADER_MANAGER.getPlatformType().toString().toLowerCase();

            FriendlyByteBuf outBuf = new FriendlyByteBuf(Unpooled.buffer());
            HandshakePacket clientHandshakePacket = new HandshakePacket(List.of(loader), AM_VERSION, MC_VERSION);
            outBuf.writeUtf(clientHandshakePacket.toJson(), Short.MAX_VALUE);

            if (serverHandshakePacket.equals(clientHandshakePacket) || (serverHandshakePacket.loaders.contains(loader) && serverHandshakePacket.amVersion.equals(AM_VERSION))) {
                LOGGER.info("Versions match {}", serverHandshakePacket.amVersion);
            } else {
                LOGGER.warn("Versions mismatch. Server: {}: Client: {}", serverHandshakePacket.amVersion, AM_VERSION);
                LOGGER.info("Trying to change automodpack version to the version required by server...");
                updateMod(handler, serverHandshakePacket.amVersion, serverHandshakePacket.mcVersion);
            }

            return CompletableFuture.completedFuture(outBuf);
        } catch (Exception e) {
            LOGGER.error("Error while handling HandshakeC2SPacket", e);
            return CompletableFuture.completedFuture(new FriendlyByteBuf(Unpooled.buffer()));
        }
    }

    private static void updateMod(ClientHandshakePacketListenerImpl handler, String serverAMVersion, String serverMCVersion) {
        if (!serverMCVersion.equals(MC_VERSION)) {
            return;
        }

        LOGGER.info("Syncing AutoModpack to server version: {}", serverAMVersion);

        ModrinthAPI automodpack = ModrinthAPI.getModSpecificVersion(SelfUpdater.AUTOMODPACK_ID, serverAMVersion, MC_VERSION);

        if (automodpack == null) {
            LOGGER.warn("Couldn't find {} version of automodpack for minecraft {} required by server", serverAMVersion, serverAMVersion);
            return;
        }

        if (!validUpdate(automodpack)) {
            LOGGER.error("Can't downgrade AutoModpack to version: {}. Server should use newer version! Please do not downgrade AutoModpack on client to prevent security vulnerabilities!", automodpack.fileVersion());
            return;
        }

        ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) handler).getConnection()).getChannel().disconnect();
        SelfUpdater.installModVersion(automodpack);
    }
}
