package pl.skidam.automodpack.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.AutoModpackMain;

public class AutoModpackToast implements Toast {
    private static Identifier TEXTURE;
    private static int WhoAreYou;

    // WhoAreYou
    // 0 == Button Clicked
    // 1 == Found Update to Modpack
    // 2 == Found Update to AutoModpack (mod)
    // 3 == No updates found to Modpack
    // 4 == No updates found to AutoModpack (mod)
    // 5 == Error
    // 6 == Cloth-Config warn

    public static void add(int WhoAreYou) {
        if (MinecraftClient.getInstance().currentScreen == null) { return;}
        AutoModpackToast.WhoAreYou = WhoAreYou;
        if (WhoAreYou == 0) {
            TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/wait.png");
        }
        if (WhoAreYou == 1 || WhoAreYou == 2) {
            TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/found-update.png");
        }
        if (WhoAreYou == 3 || WhoAreYou == 4) {
            TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/no-update.png");
        }
        if (WhoAreYou == 5) {
            TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/error.png");
        }
        if (WhoAreYou == 6) {
            TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/cloth-config.png");
        }
        ToastManager toastManager = MinecraftClient.getInstance().getToastManager();
        AutoModpackToast toast = toastManager.getToast(AutoModpackToast.class, Toast.TYPE);
        if (toast == null) {
            toastManager.add(new AutoModpackToast());
        }
    }

    @Override
    public Visibility draw(MatrixStack matrices, ToastManager manager, long startTime) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TEXTURE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        manager.drawTexture(matrices, 0, 0, 0, 0, getWidth(), getHeight());
        manager.getClient().textRenderer.draw(matrices, new TranslatableText("gui.automodpack.toast.up." + WhoAreYou), 33, 7, -256);
        manager.getClient().textRenderer.draw(matrices, new TranslatableText("gui.automodpack.toast.down." + WhoAreYou), 33, 19, -1);

        String currentScreen = MinecraftClient.getInstance().currentScreen.toString().toLowerCase();
        if (currentScreen.contains("442") || currentScreen.contains("500") || currentScreen.contains("429") || currentScreen.contains("526") || currentScreen.contains("525") || currentScreen.contains("424") || currentScreen.contains("modsscreen") || currentScreen.contains("loading") || currentScreen.contains("title") || currentScreen.contains("danger")) {
            return Visibility.SHOW;
        } else {
            return Visibility.HIDE;
        }
    }

    @Override
    public Object getType() {
        return TYPE;
    }

    @Override
    public int getWidth() {
        return 198;
    }

    @Override
    public int getHeight() {
        return 32;
    }
}

