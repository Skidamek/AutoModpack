package pl.skidam.automodpack.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

public class ToastExecutor {

    // WhoAreYou
    // 0 == Button Clicked
    // 1 == Found Update to Modpack
    // 2 == Found Update to AutoModpack (mod)
    // 3 == No updates found to Modpack
    // 4 == No updates found to AutoModpack (mod)
    // 5 == Error

    public ToastExecutor(int WhoAreYou) {

        // If game still loading toast won't show
        if (MinecraftClient.getInstance().currentScreen == null) {
            return;
        }

        SystemToast toast = SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, Text.translatable("gui.automodpack.toast.up." + WhoAreYou), Text.translatable("gui.automodpack.toast.down." + WhoAreYou));

        MinecraftClient.getInstance().getToastManager().add(toast);
    }
}