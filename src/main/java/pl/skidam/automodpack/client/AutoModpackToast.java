package pl.skidam.automodpack.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import oshi.driver.mac.Who;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.utils.Wait;

import java.util.concurrent.CompletableFuture;

public class AutoModpackToast implements Toast {
    private static Identifier TEXTURE;
    private static int WhoAreYou;
    private static int LoadingAnimationStep;
    private static String isLoadingAnimation;
    private static int bothUpdate;
    private static int oneUpdate;

    private static AutoModpackToast toast;

    // WhoAreYou
    // 0 == Button Clicked
    // 1 == Found Update to Modpack
    // 2 == Found Update to AutoModpack (mod)
    // 3 == No updates found to Modpack
    // 4 == No updates found to AutoModpack (mod)
    // 5 == Error
    // 6 == Cloth-Config warn
    // 7 == both AutoModpack and Modpack found update
    // 8 == AutoModpack updated and Modpack NOT found update
    // 9 == both AutoModpack and Modpack NOT found update
    // 10 == Automodpack NOT updated and Modpack found update

    public static void add(int WhoAreYou) {
        AutoModpackToast.WhoAreYou = WhoAreYou;
        AutoModpackMain.LOGGER.error("Adding AutoModpack Toast" + WhoAreYou);
        if (WhoAreYou == 0) {
            LoadingAnimationStep = 0;
            AutoModpackMain.LOGGER.error("" + isLoadingAnimation);
            if (isLoadingAnimation == "false" || isLoadingAnimation == null) {
                CompletableFuture.runAsync(() -> {
                    while (AutoModpackToast.WhoAreYou == 0) {
                        isLoadingAnimation = "true";
                        LoadingAnimationStep++;
                        if (LoadingAnimationStep == 8) { // number of frames in the animation - 1
                            LoadingAnimationStep = 1;
                            AutoModpackMain.LOGGER.warn("Animation step reset");
                        }
                        AutoModpackMain.LOGGER.info("Animation step: " + LoadingAnimationStep);
                        TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/loading" + LoadingAnimationStep + ".png");

                        Wait.wait(1000); // 1 fps
                    }
                    isLoadingAnimation = "false"; // TODO fix it
                });
            }
        }
        if (WhoAreYou == 1 || WhoAreYou == 2 || WhoAreYou == 7 || WhoAreYou == 9 || WhoAreYou == 10) {
            TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/found-update.png");
        }
        if (WhoAreYou == 3 || WhoAreYou == 4 || WhoAreYou == 8 || WhoAreYou == 9 || WhoAreYou == 10) {
            TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/no-update.png");
        }
        if (WhoAreYou == 5) {
            TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/error.png");
        }
        if (WhoAreYou == 6) {
            TEXTURE = new Identifier(AutoModpackMain.MOD_ID, "gui/cloth-config.png");
        }
        ToastManager toastManager = MinecraftClient.getInstance().getToastManager();
        toast = toastManager.getToast(AutoModpackToast.class, Toast.TYPE);
        AutoModpackMain.LOGGER.warn("Toast " + toast);
        if (WhoAreYou == 8 || WhoAreYou == 10) {
            toastManager.add(new AutoModpackToast());
        } else if (WhoAreYou == 7 || WhoAreYou == 9) {

        } else if (toast == null) {
            toastManager.add(new AutoModpackToast());
            MinecraftClient.getInstance().getToastManager().clear();
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

        return startTime >= 5000L ? Visibility.HIDE : Visibility.SHOW;


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

