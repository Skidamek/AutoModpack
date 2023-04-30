package pl.skidam.automodpack.networking.packet;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack.StaticVariables.LOGGER;
import static pl.skidam.automodpack.StaticVariables.VERSION;
import static pl.skidam.automodpack.networking.ModPackets.HANDSHAKE;

public class LoginC2SPacket {

    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> genericFutureListenerConsumer) {
        // Client
        String serverResponse = buf.readString();

        String loader = Platform.getPlatformType().toString().toLowerCase();

        String correctResponse = VERSION + "-" + loader;

        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(correctResponse);

        if (!serverResponse.equals(correctResponse) && !serverResponse.startsWith(VERSION)) {
            if (!serverResponse.contains(loader)) {
                LOGGER.error("Versions mismatch " + serverResponse);
            }
        }

        return CompletableFuture.completedFuture(outBuf);
    }
}
