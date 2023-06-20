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

public class MinecraftUserName {
    private static String username = "";

    public static String get() {

        if (username != null) { //TODO check also in command from relauncher class, there also can be found player username
            return username;
        }

//        if (Platform.getEnvironmentType().equals("SERVER")) return null;
//
//        if (MinecraftClient.getInstance() != null) {
//            String username = MinecraftClient.getInstance().getSession().getUsername();
//            AutoModpack.clientConfig.username = username;
//            ConfigTools.saveConfig(AutoModpack.clientConfigFile, AutoModpack.clientConfig);
//            return MinecraftUserName.username = username;
//        } else if (AutoModpack.clientConfig.username != null || !AutoModpack.clientConfig.username.equals("")) {
//            return AutoModpack.clientConfig.username;
//        } else {
//            if (System.getProperties().contains("user.name")) {
//                return "(" + System.getProperty("user.name") + ")"; // lol    pov admin: reads console... Jan Kowalski is downloading modpack XD
//            } else {
//                return null;
//            }
//        }
        return null;
    }
}