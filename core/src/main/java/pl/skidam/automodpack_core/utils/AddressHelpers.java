package pl.skidam.automodpack_core.utils;

import java.net.*;
import java.util.Enumeration;
import java.util.Objects;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class AddressHelpers {

    public static String getPublicIp() {
        String[] services = {
                "https://ip.seeip.org/json",
                "https://api.ipify.org?format=json"
        };

        for (String service : services) {
            try {
                return Objects.requireNonNull(Json.fromUrl(service)).get("ip").getAsString();
            } catch (Exception ignored) {
                // Try next service
            }
        }

        LOGGER.error("AutoModpack couldn't get your public IP address, please configure it manually.");
        return null;
    }

    public static String getLocalIp() {
        return getNetworkIp(Inet4Address.class);
    }

    public static String getLocalIpv6() {
        return getNetworkIp(Inet6Address.class);
    }

    private static String getNetworkIp(Class<? extends InetAddress> ipClass) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (ipClass.isInstance(addr) && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress().split("%")[0];
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static boolean areIpsEqual(String ip1, String ip2) {
        try {
            return InetAddress.getByName(ip1).equals(InetAddress.getByName(ip2));
        } catch (UnknownHostException ignored) {
        }
        return false;
    }

    public static String normalizeIp(String ip) {
        if (ip == null) {
            return null;
        }

        ip = ip.trim();
        if (ip.startsWith("/")) {
            ip = ip.substring(1);
        }

        if (ip.contains(":")) {
            int portIndex = ip.lastIndexOf(":");
            ip = ip.substring(0, portIndex);
        }

        if (ip.startsWith("[") && ip.endsWith("]")) {
            ip = ip.substring(1, ip.length() - 1);
        }

        return ip;
    }

    public static boolean isLocal(InetSocketAddress address) {
        if (address == null) {
            return true;
        }

        if (address.getAddress().isAnyLocalAddress()) {
            return true;
        }

        String ip = address.getAddress().getHostAddress();

        ip = normalizeIp(ip);
        String localIp = getLocalIp();
        String localIpv6 = getLocalIpv6();

        if (ip.startsWith("192.168.") || ip.startsWith("127.") || ip.startsWith("::1") || ip.startsWith("0:0:0:0:")) {
            return true;
        }

        return areIpsEqual(ip, localIp) || areIpsEqual(ip, localIpv6);
    }
}
