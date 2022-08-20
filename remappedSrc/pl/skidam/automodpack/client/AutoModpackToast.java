package pl.skidam.automodpack.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.utils.Wait;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class AutoModpackToast implements Toast {
    private static Identifier TEXTURE;
    private static int WhoAreYou;
    private static int LoadingAnimationStep;
    private static String isLoadingAnimation;
    private static int WhoAreYouBefore;

    // WhoAreYou
    // 0 == Button Clicked (Animation)
    // 1 == Found Update to Modpack
    // 2 == Found Update to AutoModpack (mod)
    // 3 == No updates found to Modpack
    // 4 == No updates found to AutoModpack (mod)
    // 5 == Error
    // 6 == Cloth-Config warn

    public static void add(int WhoAreYou) { // TODO fix this stupid toasts #fix_to_#25 https://github.com/Skidamek/AutoModpack/issues/25

        try {
            if (MinecraftClient.getInstance().currentScreen == null) return;
        } catch (NullPointerException e) {
            return;
        }

        AutoModpackToast.WhoAreYou = WhoAreYou;
        if (WhoAreYou == 0) {
            LoadingAnimationStep = 0;
            if (Objects.equals(isLoadingAnimation, "false") || isLoadingAnimation == null) {
                CompletableFuture.runAsync(() -> {
                    while (AutoModpackToast.WhoAreYou == 0) {
                        isLoadingAnimation = "true";
                        if (LoadingAnimationStep == 7) { // number of frames in the animation
                            LoadingAnimationStep = 0;
                        }
                        LoadingAnimationStep++;
                        TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/loading" + LoadingAnimationStep + ".png");

                        new Wait(100); // 10 fps
                    }
                    isLoadingAnimation = "false";
                });
            }
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
        } else if (WhoAreYouBefore == 0 || WhoAreYouBefore == 4 || WhoAreYouBefore == 3 || WhoAreYouBefore == 2 || WhoAreYouBefore == 1) {
            if (WhoAreYou == 0 || WhoAreYouBefore == 0) {
                toastManager.clear();
            }
//            if (WhoAreYou == 2 || WhoAreYou == 4) {
//                toastManager.add(new AutoModpackToast());
//            }
            // dont do anything lol
        } else if (WhoAreYou == 0) {
            toastManager.clear();
        } else {
            toastManager.add(new AutoModpackToast());
        }

        if (toast == null) {
            toastManager.add(new AutoModpackToast());
        }
    }

    public Visibility draw(MatrixStack matrices, ToastManager manager, long startTime) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TEXTURE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        manager.drawTexture(matrices, 0, 0, 0, 0, getWidth(), getHeight());
        manager.getClient().textRenderer.draw(matrices, Text.translatable("gui.automodpack.toast.up." + WhoAreYou), 33, 7, -256);
        manager.getClient().textRenderer.draw(matrices, Text.translatable("gui.automodpack.toast.down." + WhoAreYou), 33, 19, -1);


        if (WhoAreYou == 0) {
            while (WhoAreYou == 0) { // ignore it, or me if I am stupid lol
                return Visibility.SHOW;
            }
            WhoAreYouBefore = WhoAreYou;
            return Visibility.HIDE;
        } else {
            WhoAreYouBefore = WhoAreYou;
            return startTime >= 5000L ? Visibility.HIDE : Visibility.SHOW;
        }


//        if (MinecraftClient.getInstance().currentScreen != null) {
//            String currentScreen = MinecraftClient.getInstance().currentScreen.toString().toLowerCase();
//            if (currentScreen.contains("loading") || currentScreen.contains("title") || currentScreen.contains("danger") || currentScreen.contains("confirm")) {
//                return Visibility.SHOW;
//            } else {
//                return startTime >= 5000L ? Visibility.HIDE : Visibility.SHOW;
//            }
//        } else {
//            return startTime >= 5000L ? Visibility.HIDE : Visibility.SHOW;
//        }
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