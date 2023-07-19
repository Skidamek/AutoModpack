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

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;

public class Loader {

    //#if FABRIC
    public static final boolean Fabric;
    //#elseif QUILT
    //$$ public static final boolean Quilt;
    //#elseif FORGE
    //$$ public static final boolean Forge;
    //#endif

    static
    {
        //#if FABRIC
        Fabric = getPlatformType() == ModPlatform.FABRIC;
        //#elseif QUILT
        //$$ Quilt = getPlatformType() == ModPlatform.QUILT;
        //#elseif FORGE
        //$$ Forge  = getPlatformType() == ModPlatform.FORGE;
        //#endif
    }

    public static ModPlatform getPlatformType() {
        //#if FABRIC
        return ModPlatform.FABRIC;
        //#elseif QUILT
        //$$ return ModPlatform.QUILT;
        //#elseif FORGE
        //$$ return ModPlatform.FORGE;
        //#endif
    }

    public enum ModPlatform { FABRIC, QUILT, FORGE }


    //#if FABRIC

    public static String getLoaderVersion() {
        return FabricImpl.getLoaderVersion();
    }

    public static boolean isDevelopmentEnvironment() {
        return FabricImpl.isDevelopmentEnvironment();
    }

    public static boolean isModLoaded(String modId) {
        return FabricImpl.isModLoaded(modId);
    }
    public static Collection getModList() {
        return FabricImpl.getModList();
    }
    public static Path getModPath(String modId) {
        return FabricImpl.getModPath(modId);
    }
    public static String getModEnvironment(String modId) {
        return FabricImpl.getModEnvironment(modId);
    }
    public static String getModEnvironmentFromNotLoadedJar(Path file) {
        return FabricImpl.getModEnvironmentFromNotLoadedJar(file);
    }
    public static String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
        return FabricImpl.getModIdFromLoadedJar(file, checkAlsoOutOfContainer);
    }
    public static String getModIdFromNotLoadedJar(Path file) {
        return FabricImpl.getModIdFromNotLoadedJar(file);
    }
    public static String getModVersion(String modId) {
        return FabricImpl.getModVersion(modId);
    }
    public static String getModVersion(Path file) {
        return FabricImpl.getModVersion(file);
    }
    public static String getEnvironmentType() {
        return FabricImpl.getEnvironmentType();
    }

    //#elseif QUILT

//$$     public static String getLoaderVersion() {
//$$         return QuiltImpl.getLoaderVersion();
//$$     }
//$$     public static boolean isDevelopmentEnvironment() {
//$$         return QuiltImpl.isDevelopmentEnvironment();
//$$     }
//$$
//$$     public static boolean isModLoaded(String modId) {
//$$         return QuiltImpl.isModLoaded(modId);
//$$     }
//$$     public static Collection getModList() {
//$$         return QuiltImpl.getModList();
//$$     }
//$$     public static Path getModPath(String modId) {
//$$         return QuiltImpl.getModPath(modId);
//$$     }
//$$     public static String getModEnvironment(String modId) {
//$$         return QuiltImpl.getModEnvironment(modId);
//$$     }
//$$     public static String getModEnvironmentFromNotLoadedJar(Path file) {
//$$         return QuiltImpl.getModEnvironmentFromNotLoadedJar(file);
//$$     }
//$$     public static String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
//$$         return QuiltImpl.getModIdFromLoadedJar(file, checkAlsoOutOfContainer);
//$$     }
//$$     public static String getModIdFromNotLoadedJar(Path file) {
//$$         return QuiltImpl.getModIdFromNotLoadedJar(file);
//$$     }
//$$     public static String getModVersion(String modId) {
//$$         return QuiltImpl.getModVersion(modId);
//$$     }
//$$     public static String getModVersion(Path file) {
//$$         return QuiltImpl.getModVersion(file);
//$$     }
//$$     public static String getEnvironmentType() {
//$$         return QuiltImpl.getEnvironmentType();
//$$     }

    //#elseif FORGE

//$$     public static String getLoaderVersion() {
//$$         return ForgeImpl.getLoaderVersion();
//$$     }
//$$     public static boolean isDevelopmentEnvironment() {
//$$         return ForgeImpl.isDevelopmentEnvironment();
//$$     }
//$$
//$$     public static boolean isModLoaded(String modId) {
//$$         return ForgeImpl.isModLoaded(modId);
//$$     }
//$$     public static Collection getModList() {
//$$         return ForgeImpl.getModList();
//$$     }
//$$     public static Path getModPath(String modId) {
//$$         return ForgeImpl.getModPath(modId);
//$$     }
//$$     public static String getModEnvironment(String modId) {
//$$         return ForgeImpl.getModEnvironment(modId);
//$$     }
//$$     public static String getModEnvironmentFromNotLoadedJar(Path file) {
//$$         return ForgeImpl.getModEnvironmentFromNotLoadedJar(file);
//$$     }
//$$     public static String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
//$$         return ForgeImpl.getModIdFromLoadedJar(file, checkAlsoOutOfContainer);
//$$     }
//$$     public static String getModIdFromNotLoadedJar(Path file) {
//$$         return ForgeImpl.getModIdFromNotLoadedJar(file);
//$$     }
//$$     public static String getModVersion(String modId) {
//$$         return ForgeImpl.getModVersion(modId);
//$$     }
//$$     public static String getModVersion(Path file) {
//$$         return ForgeImpl.getModVersion(file);
//$$     }
//$$     public static String getEnvironmentType() {
//$$         return ForgeImpl.getEnvironmentType();
//$$    }

    //#endif
}