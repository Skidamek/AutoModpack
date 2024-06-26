package pl.skidam.automodpack_core.utils;

import com.google.gson.JsonObject;

import java.net.*;
import java.util.Enumeration;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class Ip {
    public static String getPublic() {
        JsonObject JSON = null;
        try {
            JSON = Json.fromUrl("https://ip.seeip.org/json");
        } catch (Exception e) {
            try {
                JSON = Json.fromUrl("https://api.ipify.org?format=json");
            } catch (Exception ex) {
                LOGGER.error("AutoModpack couldn't get your public IP address, you need to type it manually into config");
            }
        }
        if (JSON != null) {
            return JSON.get("ip").getAsString();
        }

        return null;
    }

    public static String getLocal() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    if (addr.isLinkLocalAddress()) {
                        continue;
                    }

                    if (addr instanceof Inet4Address)
                        return addr.getHostAddress();
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getLocalIpv6() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    if (addr instanceof Inet6Address) {
                        String hostAddress = addr.getHostAddress();
                        int idx = hostAddress.indexOf('%');
                        return idx < 0 ? hostAddress : hostAddress.substring(0, idx);
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static boolean areIpsEqual(String ip1, String ip2) {
        if (ip1 == null || ip2 == null) {
            return false;
        }

        try {
            InetAddress ia1 = InetAddress.getByName(ip1);
            InetAddress ia2 = InetAddress.getByName(ip2);
            return ia1.equals(ia2);
        } catch (UnknownHostException ignored) { }

        return false;
    }

    public static String refactorToTrueIp(String mcIp) {
        if (mcIp == null) {
            return null;
        }

        String formattedPlayerIp = mcIp.trim();

        if (formattedPlayerIp.charAt(0) == '/') {
            formattedPlayerIp = formattedPlayerIp.substring(1);
        }

        // remove port from ip
        int portIdx = formattedPlayerIp.lastIndexOf(':');
        if (portIdx > 0) {
            formattedPlayerIp = formattedPlayerIp.substring(0, portIdx);
        }

        if (formattedPlayerIp.startsWith("[") && formattedPlayerIp.endsWith("]")) {
            formattedPlayerIp = formattedPlayerIp.substring(1, formattedPlayerIp.length() - 1);

            int scopeIdx = formattedPlayerIp.indexOf('%');
            if (scopeIdx > 0) {
                formattedPlayerIp = formattedPlayerIp.substring(0, scopeIdx);
            }
        }

        return formattedPlayerIp;
    }

    public static boolean isLocal(String ipToCheck, String configLocalIp) {
        if (ipToCheck == null) {
            return true;
        }

        if (configLocalIp != null) {
            if (areIpsEqual(ipToCheck, configLocalIp)) {
                return true;
            }

            String reducedConfigLocalIp = configLocalIp;
            if (configLocalIp.split("\\.").length == 4) {
                reducedConfigLocalIp = configLocalIp.substring(0, configLocalIp.lastIndexOf("."));
            }

            if (ipToCheck.startsWith(reducedConfigLocalIp)) {
                return true;
            }
        }

        boolean isLocal1 = ipToCheck.startsWith("192.168.") || ipToCheck.startsWith("0:0:0:0:") || ipToCheck.startsWith("::") || areIpsEqual(ipToCheck, "127.0.0.1");
        if (isLocal1) {
            return true;
        }

        boolean isLocal2 = areIpsEqual(ipToCheck, getLocal()) || areIpsEqual(ipToCheck, getLocalIpv6());
        if (isLocal2) {
            return true;
        }

        return false;
    }
}
