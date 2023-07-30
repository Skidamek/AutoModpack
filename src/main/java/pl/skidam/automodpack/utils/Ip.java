/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.utils;

import com.google.gson.JsonObject;

import java.net.*;
import java.util.Enumeration;

import static pl.skidam.automodpack.GlobalVariables.LOGGER;

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
            throw new RuntimeException(e);
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
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

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
