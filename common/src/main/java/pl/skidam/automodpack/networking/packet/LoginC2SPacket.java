package pl.skidam.automodpack.networking.packet;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack.networking.ModPackets.HANDSHAKE;

public class LoginC2SPacket {

    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> genericFutureListenerConsumer) {
        // Client
        String version = buf.readString();

        String correctResponse = AutoModpack.VERSION + "-" + Platform.getPlatformType().toString().toLowerCase();

        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(correctResponse);

        if (!version.equals(correctResponse)) {
            AutoModpack.LOGGER.error("Versions mismatch " + version);
//            handler.getConnection().getPacketListener().onDisconnected(Text.of("Versions mismatch " + version));
        }

        return CompletableFuture.completedFuture(outBuf);
    }

    public static void receive(MinecraftClient client, ClientPlayNetworkHandler handler, PacketByteBuf buf, PacketSender sender) {
        // Client
        String version = buf.readString();

        String correctResponse = AutoModpack.VERSION + "-" + Platform.getPlatformType().toString().toLowerCase();

        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(correctResponse);

        if (!version.equals(correctResponse)) {
            AutoModpack.LOGGER.error("Versions mismatch " + version);
//            handler.getConnection().getPacketListener().onDisconnected(Text.of("Versions mismatch " + version));
        }

        sender.sendPacket(HANDSHAKE, outBuf);
    }
}
