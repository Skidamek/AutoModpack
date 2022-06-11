package pl.skidam.automodpack;

import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.client.StartAndCheck;
import pl.skidam.automodpack.utils.InternetConnectionCheck;

import java.io.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack.AutoModpackMain.*;
public class AutoModpackClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        LOGGER.info("Initializing AutoModpack...");

        // load saved link from ./AutoModpack/modpack-link.txt file
        String savedLink = "";
        try {
            BufferedReader br = new BufferedReader(new FileReader("./AutoModpack/modpack-link.txt"));
            savedLink = br.readLine();
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (!savedLink.equals("")) {
            link = savedLink;
            LOGGER.info("Loaded saved link to modpack: " + link);
        } else {
            LOGGER.info("No modpack link found");
        }

        InternetConnectionCheck.InternetConnectionCheck();

        ClientLoginNetworking.registerGlobalReceiver(AutoModpackMain.AM_CHECK, this::onServerRequest);
        ClientLoginNetworking.registerGlobalReceiver(AM_LINK, this::onServerLinkReceived);

        new Thread(() -> new StartAndCheck(true)).start();
    }

    private CompletableFuture<PacketByteBuf> onServerRequest(MinecraftClient minecraftClient, ClientLoginNetworkHandler clientLoginNetworkHandler, PacketByteBuf inBuf, Consumer<GenericFutureListener<? extends Future<? super Void>>> consumer) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

        buf.writeInt(1);
        return CompletableFuture.completedFuture(buf);
    }

    private CompletableFuture<PacketByteBuf> onServerLinkReceived(MinecraftClient minecraftClient, ClientLoginNetworkHandler clientLoginNetworkHandler, PacketByteBuf outBuf, Consumer<GenericFutureListener<? extends Future<? super Void>>> consumer) {
        String receivedLink = outBuf.readString(80);
        link = receivedLink;
        try {
            FileWriter fWriter = new FileWriter("./AutoModpack/modpack-link.txt");
            fWriter.flush();
            fWriter.write(receivedLink);
            fWriter.close();
        } catch (IOException e) { // ignore
        }
        LOGGER.info("Link received from server: {}. Saved to file.", receivedLink);
        return CompletableFuture.completedFuture(PacketByteBufs.empty());
    }
}
