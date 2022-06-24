package pl.skidam.automodpack.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.TranslatableText;

public class ToastExecutor {

    // WhoAreYou
    // 0 == Button Clicked
    // 1 == Found Update to Modpack
    // 2 == Found Update to AutoModpack (mod)
    // 3 == No updates found to Modpack
    // 4 == No updates found to AutoModpack (mod)
    // 5 == Error
    // 6 == Cloth-Config warn

    public ToastExecutor(int WhoAreYou) {

        // If game still loading toast won't show
        if (MinecraftClient.getInstance().currentScreen == null) {
            return;
        }

        SystemToast toast = SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, new TranslatableText("gui.automodpack.toast.up." + WhoAreYou), new TranslatableText("gui.automodpack.toast.down." + WhoAreYou));

        MinecraftClient.getInstance().getToastManager().add(toast);
    }
}