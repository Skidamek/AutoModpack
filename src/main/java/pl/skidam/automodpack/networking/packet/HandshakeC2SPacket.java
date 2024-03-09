package pl.skidam.automodpack.networking.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack_loader_core.SelfUpdater;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.platforms.ModrinthAPI;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class HandshakeC2SPacket {

    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf) {
        String serverResponse = buf.readString(32767);

        HandshakePacket serverHandshakePacket = HandshakePacket.fromJson(serverResponse);

        String loader = new LoaderManager().getPlatformType().toString().toLowerCase();

        PacketByteBuf outBuf = new PacketByteBuf(Unpooled.buffer());
        HandshakePacket clientHandshakePacket = new HandshakePacket(List.of(loader), AM_VERSION, MC_VERSION);
        outBuf.writeString(clientHandshakePacket.toJson(), 32767);

        if (serverHandshakePacket.equals(clientHandshakePacket) || (serverHandshakePacket.loaders.contains(loader) && serverHandshakePacket.amVersion.equals(AM_VERSION))) {
            LOGGER.info("Versions match " + serverHandshakePacket.amVersion);
        } else {
            LOGGER.warn("Versions mismatch " + serverHandshakePacket.amVersion);
            LOGGER.info("Trying to change automodpack version to the version required by server...");
            updateMod(handler, serverHandshakePacket.amVersion, serverHandshakePacket.mcVersion);
        }

        return CompletableFuture.completedFuture(outBuf);
    }

    private static void updateMod(ClientLoginNetworkHandler handler, String serverAMVersion, String serverMCVersion) {
        if (!serverMCVersion.equals(MC_VERSION)) {
            return;
        }

        ModrinthAPI automodpack = ModrinthAPI.getModSpecificVersion(SelfUpdater.automodpackID, serverAMVersion, MC_VERSION);

        if (automodpack == null) {
            LOGGER.warn("Couldn't find {} version of automodpack for minecraft {} required by server", serverAMVersion, serverAMVersion);
            return;
        }

        ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) handler).getConnection()).getChannel().disconnect();

        SelfUpdater.installModVersion(automodpack);
    }
}
