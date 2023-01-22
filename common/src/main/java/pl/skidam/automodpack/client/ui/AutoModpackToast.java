package pl.skidam.automodpack.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.utils.Wait;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpack.preload;

public class AutoModpackToast {

    // WhoAreYou
    // 0 == Button Clicked (Animation)
    // 1 == Found Update to Modpack
    // 2 == Found Update to AutoModpack (mod)
    // 3 == No updates found to Modpack
    // 4 == No updates found to AutoModpack (mod)
    // 5 == Error
    // 6 == Cloth-Config warn
    // 10 = Clear toasts

    public static void add(int whoAreYou) {
        if (Platform.getEnvironmentType().equals("SERVER")) return;
        if (preload) return;
        if (MinecraftClient.getInstance().currentScreen == null) return;

        InnerToast.add(whoAreYou, true);
    }

    // Thanks to inner class it won't crash
    public static class InnerToast implements Toast {
        private static Identifier TEXTURE;
        private static int whoAreYou;
        private static int loadingAnimationStep;
        private static String isLoadingAnimation;
        private static int whoWasYouBefore;

        public static void add(int whoAreYou, boolean skipChecks) { // TODO fix this stupid toasts #fix_to_#25 https://github.com/Skidamek/AutoModpack/issues/25

            if (!skipChecks) {
                AutoModpackToast.add(whoAreYou);
                return;
            }

            TEXTURE = new Identifier(AutoModpack.MOD_ID, "gui/error.png");

            InnerToast.whoAreYou = whoAreYou;
            if (whoAreYou == 0) {
                loadingAnimationStep = 0;
                if (Objects.equals(isLoadingAnimation, "false") || isLoadingAnimation == null) {
                    CompletableFuture.runAsync(() -> {
                        while (InnerToast.whoAreYou == 0) {
                            isLoadingAnimation = "true";
                            if (loadingAnimationStep == 7) { // number of frames in the animation
                                loadingAnimationStep = 0;
                            }
                            loadingAnimationStep++;
                            TEXTURE = new Identifier(AutoModpack.MOD_ID, "gui/loading" + loadingAnimationStep + ".png");

                            new Wait(100); // 10 fps
                        }
                        isLoadingAnimation = "false";
                    });
                }
            }
            if (whoAreYou == 1 || whoAreYou == 2) {
                TEXTURE = new Identifier(AutoModpack.MOD_ID, "gui/found-update.png");
            }
            if (whoAreYou == 3 || whoAreYou == 4) {
                TEXTURE = new Identifier(AutoModpack.MOD_ID, "gui/no-update.png");
            }
            if (whoAreYou == 5) {
                TEXTURE = new Identifier(AutoModpack.MOD_ID, "gui/error.png");
            }
            if (whoAreYou == 6) {
                TEXTURE = new Identifier(AutoModpack.MOD_ID, "gui/cloth-config.png");
            }
            ToastManager toastManager = MinecraftClient.getInstance().getToastManager();
            AutoModpackToast.InnerToast toast = toastManager.getToast(AutoModpackToast.InnerToast.class, Toast.TYPE);

            if (whoAreYou == 10) {
                toastManager.clear();
                return;
            }

            // SO TRASHSHSHSHHSHHHHHY

            if (toast == null) {
                toastManager.add(new AutoModpackToast.InnerToast());
            } else if (whoWasYouBefore == 0 || whoWasYouBefore == 4 || whoWasYouBefore == 3 || whoWasYouBefore == 2 || whoWasYouBefore == 1) {
                if (whoAreYou == 0 && whoWasYouBefore != 0) {
                    toastManager.clear();
                }
                if (whoAreYou == 2 || whoAreYou == 4) {
                    if (whoWasYouBefore == 0) {
                        toastManager.clear();
                    } else if (whoAreYou == 4 && whoWasYouBefore == 1) {
                        toastManager.clear();
                    }
                    toastManager.add(new AutoModpackToast.InnerToast());
                }
                // dont do anything lol
            } else if (whoAreYou == 0) {
                toastManager.clear();
            } else {
                toastManager.add(new AutoModpackToast.InnerToast());
            }
        }

        public Visibility draw(MatrixStack matrices, ToastManager manager, long startTime) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, TEXTURE);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            manager.drawTexture(matrices, 0, 0, 0, 0, getWidth(), getHeight());
            manager.getClient().textRenderer.draw(matrices, Text.translatable("gui.automodpack.toast.up." + whoAreYou), 33, 7, -256);
            manager.getClient().textRenderer.draw(matrices, Text.translatable("gui.automodpack.toast.down." + whoAreYou), 33, 19, -1);


            if (whoAreYou == 0) {
                whoWasYouBefore = whoAreYou;
                if (MinecraftClient.getInstance().currentScreen != null) {
                    return startTime >= 5000L ? Visibility.HIDE : Visibility.SHOW;
                } else {
                    return Visibility.SHOW;
                }
            } else {
                whoWasYouBefore = whoAreYou;
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
}