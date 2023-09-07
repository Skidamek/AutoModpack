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

package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.Collection;

public interface LoaderService {

    enum ModPlatform { FABRIC, QUILT, FORGE }
    enum EnvironmentType { CLIENT, SERVER, BOTH }


    ModPlatform getPlatformType();

    boolean isModLoaded(String modId);

    Collection<?> getModList();

    String getLoaderVersion();

    Path getModPath(String modId);

    EnvironmentType getEnvironmentType();

    EnvironmentType getModEnvironmentFromNotLoadedJar(Path file);

    String getModVersion(String modId);

    String getModVersion(Path file);
    boolean isDevelopmentEnvironment();

    EnvironmentType getModEnvironment(String modId);

    String getModId(Path file, boolean checkAlsoOutOfContainer);
    String getModIdFromNotLoadedJar(Path file);
}