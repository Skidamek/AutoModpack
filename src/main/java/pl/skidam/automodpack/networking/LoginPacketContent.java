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

package pl.skidam.automodpack.networking;

import com.google.gson.Gson;

public class LoginPacketContent {
    public String automodpackVersion;
    public String mcVersion;
    public String modpackName;
    public String loader;
    public String loaderVersion;
    public String link;

    public LoginPacketContent(String automodpackVersion, String mcVersion, String modpackName, String loader, String loaderVersion, String link) {
        this.automodpackVersion = automodpackVersion;
        this.mcVersion = mcVersion;
        this.modpackName = modpackName;
        this.loader = loader;
        this.loaderVersion = loaderVersion;
        this.link = link;
    }

    public String toJson() {
        Gson gson = new Gson();
        return gson.toJson(this);
    }

    public static LoginPacketContent fromJson(String json) {
        Gson gson = new Gson();
        return gson.fromJson(json, LoginPacketContent.class);
    }
}
