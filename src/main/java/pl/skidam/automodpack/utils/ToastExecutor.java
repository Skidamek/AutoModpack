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

    public ToastExecutor(int WhoAreYou) {

        // If game still loading toast won't show
        if (MinecraftClient.getInstance().currentScreen == null) {
            return;
        }

        SystemToast toast = null;
        if (WhoAreYou == 0) {
            toast = SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, new TranslatableText("gui.automodpack.toast.up.0"), new TranslatableText("gui.automodpack.toast.down.0"));
        }
        if (WhoAreYou == 1) {
            toast = SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, new TranslatableText("gui.automodpack.toast.up.1"), new TranslatableText("gui.automodpack.toast.down.1"));
        }
        if (WhoAreYou == 2) {
            toast = SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, new TranslatableText("gui.automodpack.toast.up.2"), new TranslatableText("gui.automodpack.toast.down.2"));
        }
        if (WhoAreYou == 3) {
            toast = SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, new TranslatableText("gui.automodpack.toast.up.3"), new TranslatableText("gui.automodpack.toast.down.3"));
        }
        if (WhoAreYou == 4) {
            toast = SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, new TranslatableText("gui.automodpack.toast.up.4"), new TranslatableText("gui.automodpack.toast.down.4"));
        }
        if (WhoAreYou == 5) {
            toast = SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, new TranslatableText("gui.automodpack.toast.up.8"), new TranslatableText("gui.automodpack.toast.down.8"));
        }

        MinecraftClient.getInstance().getToastManager().add(toast);

    }
}