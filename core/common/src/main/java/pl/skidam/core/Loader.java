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

package pl.skidam.core;

import java.nio.file.Path;
import java.util.Collection;

public interface Loader {

    default ModPlatform getPlatformType() {
        return ModPlatform.FABRIC;
    }

    enum ModPlatform { FABRIC, QUILT, FORGE }


    default boolean isModLoaded(String modId) {
        throw new AssertionError();
    }

    default Collection getModList() {
        throw new AssertionError();
    }

    default String getLoaderVersion() {
        throw new AssertionError();
    }


    default Path getModPath(String modId) {
        throw new AssertionError();
    }

    default String getEnvironmentType() {
        throw new AssertionError();
    }

    default String getModEnvironmentFromNotLoadedJar(Path file) {
        throw new AssertionError();
    }

    default String getModVersion(String modId) {
        throw new AssertionError();
    }

    default String getModVersion(Path file) {
        throw new AssertionError();
    }
    default boolean isDevelopmentEnvironment() {
        throw new AssertionError();
    }

    default String getModEnvironment(String modId) {
        throw new AssertionError();
    }

    default String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
        throw new AssertionError();
    }

    default String getModIdFromNotLoadedJar(Path file) {
        throw new AssertionError();
    }
}