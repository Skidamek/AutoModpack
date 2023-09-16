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

package pl.skidam.automodpack.client.ui.widget;

import net.minecraft.util.Identifier;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;

import static pl.skidam.automodpack_common.GlobalVariables.MOD_ID;

public class Badge {
    private static final Identifier MODRINTH_ICON = new Identifier(MOD_ID, "gui/platform/logo-modrinth.png");
    private static final Identifier CURSEFORGE_ICON = new Identifier(MOD_ID, "gui/platform/logo-curseforge.png");
    private static final int textureSize = 32;

    public static void renderModrinthBadge(VersionedMatrices matrices, int x, int y) {
        VersionedScreen.drawTexture(MODRINTH_ICON, matrices, x, y, 0, 0, textureSize, textureSize, textureSize, textureSize);
    }

    public static void renderCurseForgeBadge(VersionedMatrices matrices, int x, int y) {
        VersionedScreen.drawTexture(CURSEFORGE_ICON, matrices, x, y, 0, 0, textureSize, textureSize, textureSize, textureSize);
    }
}
