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

package pl.skidam.automodpack.loaders;

//#if FORGE
//$$ import java.io.File;
//$$ import java.util.Collection;
//$$ import net.minecraftforge.api.distmarker.Dist;
//$$ import net.minecraftforge.fml.loading.FMLLoader;
//$$ import net.minecraftforge.fml.ModList;
//$$
//$$ public class ForgeImpl {
//$$     public static boolean isDevelopmentEnvironment() {
//$$         return !FMLLoader.isProduction();
//$$     }
//$$
//$$     public static boolean isModLoaded(String modId) {
//$$        return ModList.get().isLoaded(modId);
//$$    }
//$$
//$$     public static Collection getModList() {
//$$         return null;
//$$     }
//$$
//$$     public static File getModPath(String modId) {
//$$        return null;
//$$    }
//$$
//$$     public static String getModEnvironment(String modId) {
//$$         return null;
//$$
//$$    }
//$$
//$$     public static String getModEnvironmentFromNotLoadedJar(File file) {
//$$         return null;
//$$     }
//$$
//$$     public static String getModIdFromLoadedJar(File file, boolean checkAlsoOutOfContainer) {
//$$         return null;
//$$     }
//$$
//$$     public static String getModIdFromNotLoadedJar(File file) {
//$$         return null;
//$$     }
//$$
//$$     public static String getModVersion(String modId) {
//$$         return null;
//$$     }
//$$
//$$     public static String getModVersion(File file) {
//$$         return null;
//$$     }
//$$
//$$     public static String getEnvironmentType() {
//$$         Dist dist = FMLLoader.getDist();
//$$         if (dist.isClient()) {
//$$             return "CLIENT";
//$$         } else if (dist.isDedicatedServer()) {
//$$             return "SERVER";
//$$         } else {
//$$             return "UNKNOWN";
//$$         }
//$$     }
//$$ }
//#endif