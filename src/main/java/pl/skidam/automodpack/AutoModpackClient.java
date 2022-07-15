package pl.skidam.automodpack;

import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.client.StartAndCheck;
import pl.skidam.automodpack.client.modpack.CheckModpack;

import java.io.*;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.ValidateURL.ValidateURL;
public class AutoModpackClient implements ClientModInitializer {

    public static boolean isOnServer;
    @Override
    public void onInitializeClient() {

        LOGGER.info("Initializing AutoModpack...");

        isOnServer = false;
        CheckModpack.isCheckUpdatesButtonClicked = false;

        // load saved link from ./AutoModpack/modpack-link.txt file
        String savedLink = "";
        try {
            File modpack_link = new File("./AutoModpack/modpack-link.txt");
            FileReader fr = new FileReader(modpack_link);
            Scanner inFile = new Scanner(fr);
            if (inFile.hasNextLine()) {
                savedLink = inFile.nextLine();
            }
            inFile.close();
        } catch (Exception e) { // ignore
        }

        if (!savedLink.equals("")) {
            if (ValidateURL(savedLink)) {
                link = savedLink;
                LOGGER.info("Loaded saved link to modpack: " + link);
            } else {
                LOGGER.error("Saved link is not valid url or is not end with /modpack");
            }
        }

        // packets
        ClientLoginNetworking.registerGlobalReceiver(AM_CHECK, this::onServerRequest);
        ClientLoginNetworking.registerGlobalReceiver(AM_LINK, this::onServerLinkReceived);

        // register
        ClientLoginConnectionEvents.QUERY_START.register((clientLoginNetworkHandler, minecraftClient) -> isOnServer = true);
        ClientLoginConnectionEvents.DISCONNECT.register((clientLoginNetworkHandler, minecraftClient) -> isOnServer = false);

        new StartAndCheck(true, false);
    }

    private CompletableFuture<PacketByteBuf> onServerRequest(MinecraftClient minecraftClient, ClientLoginNetworkHandler clientLoginNetworkHandler, PacketByteBuf inBuf, Consumer<GenericFutureListener<? extends Future<? super Void>>> consumer) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

        buf.writeInt(1);
        return CompletableFuture.completedFuture(buf);
    }

    private CompletableFuture<PacketByteBuf> onServerLinkReceived(MinecraftClient minecraftClient, ClientLoginNetworkHandler clientLoginNetworkHandler, PacketByteBuf outBuf, Consumer<GenericFutureListener<? extends Future<? super Void>>> consumer) {
        String receivedLink = outBuf.readString(100);
        link = receivedLink;
        try {
            FileWriter fWriter = new FileWriter("./AutoModpack/modpack-link.txt");
            fWriter.flush();
            fWriter.write(receivedLink);
            fWriter.close();
        } catch (IOException e) { // ignore
        }
        LOGGER.info("Link received from server: {}. Saved to file.", receivedLink);
        new StartAndCheck(false, true);
        return CompletableFuture.completedFuture(PacketByteBufs.empty());
    }
}
