package pl.skidam.automodpack;

import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
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

        try {
            BufferedReader br = new BufferedReader(new FileReader("./AutoModpack/modpack-link.txt"));
            if (br.readLine() == null) {
                link = br.readLine();
            } else {
                link = "null";
            }
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        InternetConnectionCheck.InternetConnectionCheck();

//        ClientPlayNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_S2C, (client, handler, buf, responseSender) -> {
//
//            LOGGER.info("Received AutoModpack packet from server!");
//
//            CompletableFuture.runAsync(() -> {
//                String receivedLink = buf.readString(50);
//
//                try {
//                    FileWriter fWriter = new FileWriter("./AutoModpack/modpack-link.txt");
//                    fWriter.flush();
//                    fWriter.write(receivedLink);
//                    fWriter.close();
//                } catch (IOException e) { // ignore
//                }
//
//                link = receivedLink;
//                LOGGER.info("Modpack link received from server: {}. Saved to file.", receivedLink);
//            });
//
//            while (true) {
//                if (ClientPlayNetworking.canSend(AutoModpackMain.PACKET_C2S)) {
//                    ClientPlayNetworking.send(AutoModpackMain.PACKET_C2S, PacketByteBufs.empty());
//                    break;
//                }
//            }
//        });

        ClientLoginNetworking.registerGlobalReceiver(AutoModpackMain.AM_CHECK, this::onServerRequest);

        new Thread(() -> new StartAndCheck(true)).start();
    }

    private CompletableFuture<PacketByteBuf> onServerRequest(MinecraftClient minecraftClient, ClientLoginNetworkHandler clientLoginNetworkHandler, PacketByteBuf inBuf, Consumer<GenericFutureListener<? extends Future<? super Void>>> consumer) {
        LOGGER.info("123Received AutoModpack packet from server!");

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

        buf.writeInt(1);
        return CompletableFuture.completedFuture(buf);
    }

//    private CompletableFuture<PacketByteBuf> onServerRequest(MinecraftClient minecraftClient, ClientPlayNetworkHandler handler, PacketByteBuf packetByteBuf, Consumer<GenericFutureListener<? extends Future<? super Void>>> consumer) {
//        LOGGER.info("123Received AutoModpack packet from server!");
//
//        return null;
//    }


//    private CompletableFuture<PacketByteBuf> onServerRequest(MinecraftClient minecraft, MinecraftClient client, PacketByteBuf inbuf, Consumer<GenericFutureListener<? extends Future<? super Void>>> consumer) {
//
//        // Send whatever to server for check if client has AutoModpack installed
//        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
//
//        buf.writeInt(1);
//        return CompletableFuture.completedFuture(buf);
//    }
}
