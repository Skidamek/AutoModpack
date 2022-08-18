package pl.skidam.automodpack;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.client.StartAndCheck;
import pl.skidam.automodpack.client.modpack.CheckModpack;

import java.io.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class AutoModpackClient implements ClientModInitializer {

    public static boolean Checked = false;
    public static boolean isOnServer;
    public static String serverIP;
    public static final File modpack_link = new File("./AutoModpack/modpack-link.txt");

    @Override
    public void onInitializeClient() {

        LOGGER.info("Initializing AutoModpack...");

        // Packet
        ClientLoginNetworking.registerGlobalReceiver(AutoModpackMain.AM_LINK, AutoModpackClient::onServerLoginLinkReceived);
        ClientPlayNetworking.registerGlobalReceiver(AutoModpackMain.AM_LINK, AutoModpackClient::onServerPlayLinkReceived); // for velocity support

        isOnServer = false;
        CheckModpack.isCheckUpdatesButtonClicked = false;

        // Register
        ClientLoginConnectionEvents.QUERY_START.register((clientLoginNetworkHandler, minecraftClient) -> {
            serverIP = clientLoginNetworkHandler.getConnection().getAddress().toString();
            isOnServer = true;
        });
        ClientLoginConnectionEvents.DISCONNECT.register((clientLoginNetworkHandler, minecraftClient) -> isOnServer = false);
    }

    private static void onServerPlayLinkReceived(MinecraftClient minecraftClient, ClientPlayNetworkHandler clientPlayNetworkHandler, PacketByteBuf inBuf, PacketSender sender) {
        String receivedLink = inBuf.readString(100);
        link = receivedLink;
        try {
            FileWriter fWriter = new FileWriter(modpack_link);
            fWriter.flush();
            fWriter.write(receivedLink);
            fWriter.close();
        } catch (IOException e) { // ignore
        }
        LOGGER.info("Link received from server through proxy: {}. Saved to file.", receivedLink);
        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString("1");
        CompletableFuture.completedFuture(outBuf);
        isVelocity = true;
        new StartAndCheck(false, true);
    }

    private static CompletableFuture<PacketByteBuf> onServerLoginLinkReceived(MinecraftClient minecraftClient, ClientLoginNetworkHandler clientLoginNetworkHandler, PacketByteBuf inBuf, Consumer<GenericFutureListener<? extends Future<? super Void>>> consumer) {
        String receivedLink = inBuf.readString(100);
        link = receivedLink;
        try {
            FileWriter fWriter = new FileWriter(modpack_link);
            fWriter.flush();
            fWriter.write(receivedLink);
            fWriter.close();
        } catch (IOException e) { // ignore
        }
        LOGGER.info("Link received from server: {}. Saved to file.", receivedLink);
        CompletableFuture.runAsync(() -> {
            new StartAndCheck(false, true);
        });
        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString("1");
        return CompletableFuture.completedFuture(outBuf);
    }
}
