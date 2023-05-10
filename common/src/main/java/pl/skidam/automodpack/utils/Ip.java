package pl.skidam.automodpack.utils;

import com.google.gson.JsonObject;

import java.net.DatagramSocket;
import java.net.InetAddress;

import static pl.skidam.automodpack.StaticVariables.LOGGER;

public class Ip {
    public static String getPublic() {
        JsonObject JSON = null;
        try {
            JSON = Json.fromUrl("https://ip.seeip.org/json");
        } catch (Exception e) {
            try {
                JSON = Json.fromUrl("https://api.ipify.org?format=json");
            } catch (Exception ex) {
                LOGGER.error("Can't get your IP address, you need to type it manually into config");
                ex.printStackTrace();
            }
        }
        if (JSON != null) {
            return JSON.get("ip").getAsString();
        }
        return null;
    }

    public static String getLocal() {
        String ip = null;
        try(final DatagramSocket socket = new DatagramSocket()){
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            ip = socket.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ip;
    }
}
