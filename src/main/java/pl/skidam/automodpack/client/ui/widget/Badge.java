package pl.skidam.automodpack.client.ui.widget;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.init.Common;

import static pl.skidam.automodpack_core.GlobalVariables.MOD_ID;

import net.minecraft.resources.ResourceLocation;

public class Badge {
    private static final ResourceLocation MODRINTH_ICON = Common.id("gui/platform/logo-modrinth.png");
    private static final ResourceLocation CURSEFORGE_ICON = Common.id("gui/platform/logo-curseforge.png");
    private static final int textureSize = 32;

    public static void renderModrinthBadge(VersionedMatrices matrices, int x, int y) {
        VersionedScreen.drawTexture(MODRINTH_ICON, matrices, x, y, 0, 0, textureSize, textureSize, textureSize, textureSize);
    }

    public static void renderCurseForgeBadge(VersionedMatrices matrices, int x, int y) {
        VersionedScreen.drawTexture(CURSEFORGE_ICON, matrices, x, y, 0, 0, textureSize, textureSize, textureSize, textureSize);
    }
}
