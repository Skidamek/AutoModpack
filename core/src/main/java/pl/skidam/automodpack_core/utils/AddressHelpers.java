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

    // This method has a benefit of not attempting to resolve the hostname or reverse DNS lookup
    // It will just return hostname or address as a string
    // Using getHostString() method breaks on srv records and returns the whole dns record
    public static String getHostNameOrAddress(InetSocketAddress socketAddress) {
        if (socketAddress == null) {
            return null;
        }

        String host = socketAddress.getHostString();

        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Address or hostname is empty");
        }

        // If the host ends with a dot, remove it (fun fact: its a valid domain but not for us)
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }

        // If theres `_minecraft._tcp.` in the host, remove it and everything before it
        String srvRecord = "_minecraft._tcp.";
        if (host.contains(srvRecord)) {
            host = host.substring(host.indexOf(srvRecord) + srvRecord.length());
        }

        return host;
    }

    public static InetSocketAddress parse(String address) {
        if (address == null) return null;
       InetSocketAddress socketAddress = null;
        try {
            int portIndex = address.lastIndexOf(':');
            if (portIndex != -1) {
                String host = address.substring(0, portIndex);
                String port = address.substring(portIndex + 1);
                if (port.matches("\\d+")) {
                    socketAddress = InetSocketAddress.createUnresolved(host, Integer.parseInt(port));
                }
            }
            if (socketAddress == null) {
                socketAddress = InetSocketAddress.createUnresolved(address, 0);
            }
        } catch (Exception e) {
            LOGGER.error("Error while parsing address", e);
        }

        return socketAddress;
    }

    public static boolean isLocal(String address) {
        if (address == null) {
            return true;
        }

        address = normalizeIp(address);
        if (address.startsWith("192.168.") || address.startsWith("127.") || address.startsWith("::1") || address.startsWith("0:0:0:0:")) {
            return true;
        }

        String localIp = getLocalIp();
        String localIpv6 = getLocalIpv6();
        return areIpsEqual(address, localIp) || areIpsEqual(address, localIpv6);
    }
}
