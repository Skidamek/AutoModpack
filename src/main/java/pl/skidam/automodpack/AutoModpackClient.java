package pl.skidam.automodpack;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import pl.skidam.automodpack.Client.StartAndCheck;
import pl.skidam.automodpack.utils.InternetConnectionCheck;

import static pl.skidam.automodpack.AutoModpackMain.*;
public class AutoModpackClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {

        LOGGER.info("Initializing AutoModpack...");

        InternetConnectionCheck.InternetConnectionCheck();


        ClientPlayNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_S2C, (client, handler, buf, responseSender) -> {
            LOGGER.error("Received packet from server!");

            client.execute(() -> {
                ClientPlayNetworking.send(AutoModpackMain.PACKET_C2S, PacketByteBufs.empty());
            });

            LOGGER.error("Sent response to server!");

        });


        new Thread(() -> new StartAndCheck(true)).start();
    }
}
