package pl.skidam.automodpack;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.TranslatableText;

public class ToastExecutor {

    public ToastExecutor() {
        SystemToast toast = SystemToast.create(MinecraftClient.getInstance(), SystemToast.Type.TUTORIAL_HINT, new TranslatableText("gui.automodpack.button.update.tooltip"), new TranslatableText("gui.automodpack.button.update.tooltip"));
        MinecraftClient.getInstance().getToastManager().add(toast);
    }
}