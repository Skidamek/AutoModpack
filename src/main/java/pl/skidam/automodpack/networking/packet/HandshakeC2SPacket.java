package pl.skidam.automodpack.networking.packet;

import io.netty.buffer.Unpooled;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack_core.protocol.iroh.EndpointProof;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;
import pl.skidam.automodpack_core.protocol.iroh.IrohTransportSupport;
import pl.skidam.automodpack_core.utils.SemanticVersion;
import pl.skidam.automodpack_loader_core.SelfUpdater;
import pl.skidam.automodpack_loader_core.platforms.ModrinthAPI;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;
import pl.skidam.automodpack.networking.ModPackets;

import static pl.skidam.automodpack_core.Constants.*;

public class HandshakeC2SPacket {

    public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
        FriendlyByteBuf outBuf = new FriendlyByteBuf(Unpooled.buffer());

        try {
            String serverResponse = buf.readUtf(Short.MAX_VALUE);
            HandshakePacket serverHandshakePacket = HandshakePacket.fromJson(serverResponse);

            String loader = LOADER_MANAGER.getPlatformType().toString().toLowerCase();
            if (!serverHandshakePacket.loaders.contains(loader)) {
                LOGGER.warn("Loader mismatch. Server accepts: {}: Client: {}", serverHandshakePacket.loaders, loader);
                return CompletableFuture.completedFuture(outBuf);
            }

            updateIfNeededMod(handler, serverHandshakePacket.amVersion, serverHandshakePacket.mcVersion);

            var originalServerAddress = ModPackets.getOriginalServerAddress();
            HandshakePacket clientHandshakePacket = new HandshakePacket(
                Set.of(loader),
                AM_VERSION,
                MC_VERSION,
                HandshakePacket.CURRENT_PROTOCOL_VERSION,
                originalServerAddress == null ? null : originalServerAddress.getHostString(),
                originalServerAddress == null ? null : originalServerAddress.getPort()
            );
            outBuf.writeUtf(clientHandshakePacket.toJson(), Short.MAX_VALUE);
            outBuf.writeBytes(EndpointProof.create(IrohIdentity.loadOrCreateEndpointId(IrohTransportSupport.IROH_KEY_FILE)));

            return CompletableFuture.completedFuture(outBuf);
        } catch (Exception e) {
            LOGGER.error("Error while handling HandshakeC2SPacket", e);
            return CompletableFuture.completedFuture(outBuf);
        }
    }

    private static void updateIfNeededMod(ClientHandshakePacketListenerImpl handler, String serverAMVersion, String serverMCVersion) {
        if (!clientConfig.syncAutoModpackVersion) {
            LOGGER.warn("AutoModpack version syncing is disabled in client config. Cannot sync to server version: {}", serverAMVersion);
            return;
        }

        if (!serverMCVersion.equals(MC_VERSION)) {
            return;
        }

        if (AM_VERSION.equals(serverAMVersion)) {
            LOGGER.info("Versions match {}", AM_VERSION);
            return;
        } else {
            LOGGER.warn("Versions mismatch. Server: {}: Client: {}", serverAMVersion, AM_VERSION);
        }

        LOGGER.info("Syncing AutoModpack to server version: {}", serverAMVersion);

        ModrinthAPI automodpack = ModrinthAPI.getModSpecificVersion(SelfUpdater.AUTOMODPACK_ID, serverAMVersion, MC_VERSION);

        if (automodpack == null) {
            LOGGER.warn("Couldn't find {} version of automodpack for minecraft {} required by server", serverAMVersion, serverAMVersion);
            return;
        }

        SemanticVersion semver = SemanticVersion.parse(automodpack.fileVersion());

        // Disconnect and install only if the update is valid
        if (SelfUpdater.validUpdate(semver)) {
            ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) handler).getConnection()).getChannel().disconnect();
            SelfUpdater.installModVersion(automodpack);
        }
    }
}
