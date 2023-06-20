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

import net.minecraft.util.Identifier;

import static pl.skidam.automodpack.StaticVariables.*;

public class ModPackets {
    public static final Identifier HANDSHAKE = new Identifier(MOD_ID, "handshake");
    public static final Identifier LINK = new Identifier(MOD_ID, "link");

    public static void registerC2SPackets() {
//#if FABRICLIKE
        ModPacketsImpl.registerC2SPackets();
//#endif
    }

    public static void registerS2CPackets() {
//#if FABRICLIKE
        ModPacketsImpl.registerS2CPackets();
//#endif
    }

}
