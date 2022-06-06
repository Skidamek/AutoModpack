package pl.skidam.automodpack;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import pl.skidam.automodpack.client.StartAndCheck;
import pl.skidam.automodpack.utils.InternetConnectionCheck;

import java.io.*;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.*;
public class AutoModpackClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        LOGGER.info("Initializing AutoModpack...");

        try {
            BufferedReader br = new BufferedReader(new FileReader("./AutoModpack/modpack-link.txt"));
            String line;
            while ((line = br.readLine()) != null) {
                link = line;
            }
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        InternetConnectionCheck.InternetConnectionCheck();

        ClientPlayNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_S2C, (client, handler, buf, responseSender) -> {


            CompletableFuture.runAsync(() -> {
                // log message from packet
                link = buf.readString(200);

                try {
                    FileWriter fWriter = new FileWriter("./AutoModpack/modpack-link.txt");
                    fWriter.flush();
                    fWriter.write(link);
                    fWriter.close();
                } catch (IOException e) { // ignore
                }

                LOGGER.info("Modpack link received from server: {}. Saved to file.", link);
            });


            CompletableFuture.runAsync(() -> {
                while (true) {
                    try {
                        if (ClientPlayNetworking.canSend(AutoModpackMain.PACKET_C2S)) {
                            ClientPlayNetworking.send(AutoModpackMain.PACKET_C2S, PacketByteBufs.empty());
                            break;
                        }
                        Thread.sleep(1);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

        });

        new Thread(() -> new StartAndCheck(true)).start();
    }
}
