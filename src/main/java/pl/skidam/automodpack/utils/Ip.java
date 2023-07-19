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

import java.net.DatagramSocket;
import java.net.InetAddress;

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
        String ip = null;
        try (final DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            ip = socket.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ip;
    }
}
