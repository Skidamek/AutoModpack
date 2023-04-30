package pl.skidam.automodpack.networking.fabric;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.mixin.ServerLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.packet.LinkC2SPacket;
import pl.skidam.automodpack.networking.packet.LinkS2CPacket;
import pl.skidam.automodpack.networking.packet.LoginC2SPacket;
import pl.skidam.automodpack.networking.packet.LoginS2CPacket;

import java.util.*;
import java.util.concurrent.FutureTask;

import static pl.skidam.automodpack.StaticVariables.*;
import static pl.skidam.automodpack.networking.ModPackets.HANDSHAKE;
import static pl.skidam.automodpack.networking.ModPackets.LINK;

public class ModPacketsImpl {

    // UUID, acceptLogin
    public static List<UUID> acceptLogin = new ArrayList<>();

    public static void registerC2SPackets() {
        // Client
        ClientLoginNetworking.registerGlobalReceiver(HANDSHAKE, LoginC2SPacket::receive);
        ClientLoginNetworking.registerGlobalReceiver(LINK, LinkC2SPacket::receive);
    }

    public static void registerS2CPackets() {
        // Server
        ServerLoginNetworking.registerGlobalReceiver(HANDSHAKE, LoginS2CPacket::receive);
        ServerLoginNetworking.registerGlobalReceiver(LINK, LinkS2CPacket::receive);

        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, sync) -> {

            GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
            UUID uniqueId = profile.getId();

            FutureTask<?> future = new FutureTask<>(() -> {
                for (int i = 0; i <= 300; i++) {
                    Thread.sleep(50);

                    if (acceptLogin.contains(uniqueId)) {
                        acceptLogin.remove(uniqueId);
                        break;
                    }

                    if (i == 300) {
                        LOGGER.error("Timeout login for " + profile.getName() + " (" + uniqueId.toString()  + ")");
                        Text reason = TextHelper.literal("AutoModpack - timeout");
                        ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
                        connection.send(new LoginDisconnectS2CPacket(reason));
                        connection.disconnect(reason);
                    }
                }

                return null;
            });

            // Execute the task on a worker thread as not to block the server thread
            Util.getMainWorkerExecutor().execute(future);

            PacketByteBuf buf = PacketByteBufs.create();
            String correctResponse = VERSION + "-" + Platform.getPlatformType().toString().toLowerCase();
            if (serverConfig.allowFabricQuiltPlayers) {
                correctResponse = VERSION + "-" + "fabric&quilt";
            }
            buf.writeString(correctResponse);
            sender.sendPacket(HANDSHAKE, buf);

            sync.waitFor(future);
        });
    }
}
