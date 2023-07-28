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

package pl.skidam.automodpack.mixin.forge;

//#if FORGE
//$$
//$$ import net.minecraft.util.Identifier;
//$$ import net.minecraftforge.network.NetworkDirection;
//$$ import net.minecraftforge.network.NetworkRegistry;
//$$ import net.minecraftforge.network.ServerStatusPing;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.Overwrite;
//$$
//$$ import java.util.Collections;
//$$ import java.util.List;
//$$ import java.util.Map;
//$$
//$$@Mixin(value = NetworkRegistry.class, remap = false)
//$$public class NetworkRegistryMixin {
//$$
//$$    /**
//$$    * @author Skidam
//$$    * @reason Disable mod list check to allow AutoModpack perform its own checks and download required mods.
//$$    */
//$$
//$$    @Overwrite
//$$    public static List<NetworkRegistry.LoginPayload> gatherLoginPayloads(final NetworkDirection direction, boolean isLocal) {
//$$         return Collections.emptyList();
//$$    }
//$$}
//$$
//#else

import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = Util.class, remap = false)
public class NetworkRegistryMixin {

}

//#endif