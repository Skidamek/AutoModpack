//package pl.skidam.automodpack.mixin;
//
//import net.minecraft.client.MinecraftClient;
//import net.minecraft.client.gui.screen.Screen;
//import net.minecraft.client.util.NarratorManager;
//import net.minecraft.client.world.ClientWorld;
//import net.minecraft.util.Util;
//import net.minecraft.util.crash.CrashReport;
//import org.spongepowered.asm.mixin.Final;
//import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.Shadow;
//import org.spongepowered.asm.mixin.injection.At;
//import org.spongepowered.asm.mixin.injection.Inject;
//import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//import pl.skidam.automodpack.AutoModpack;
//import pl.skidam.automodpack.ReLauncher;
//import pl.skidam.automodpack.ui.Windows;
//
//import java.util.function.Supplier;
//
//// Overwrite default stop method, to open warning window that game is automatically restarting
//
//@Mixin(value = MinecraftClient.class, priority = 2137)
//public abstract class StopMixin {
//    @Final
//    @Shadow
//    private NarratorManager narratorManager;
//
//    @Shadow
//    public ClientWorld world;
//
//    @Shadow
//    public Screen currentScreen;
//
//    @Shadow
//    private Supplier<CrashReport> crashReportSupplier;
//
//    @Shadow
//    public abstract void disconnect();
//
//    @Shadow
//    public abstract void close();
//
//    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
//    private void onStop(CallbackInfo ci) {
//
//        try {
//            this.narratorManager.destroy();
//        } catch (Throwable ignored) {
//        }
//
//        try {
//            if (this.world != null) {
//                this.world.disconnect();
//            }
//
//            this.disconnect();
//        } catch (Throwable ignored) {
//        }
//
//        if (this.currentScreen != null) {
//            this.currentScreen.removed();
//        }
//
//        this.close();
//
//        ci.cancel();
//    }
//
//    @Inject(method = "stop", at = @At("RETURN"))
//    private void onStopReturn(CallbackInfo ci) {
//
//        System.out.println("Automodpack: Stop");
//
//        Util.nanoTimeSupplier = System::nanoTime;
//        if (this.crashReportSupplier == null) {
//
//            System.out.println("Automodpack: Stop - no crash report");
//
//            // automodpack - exit
//            if (!AutoModpack.preload && ReLauncher.openWindow) {
//                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
//                System.out.println("Automodpack: Exit");
//                if (isWindows) {
//                    new Windows().restartingWindow();
//                } else {
//                    new Windows().errorRestartingWindow();
//                }
//            }
//            // automodpack - exit
//
//            System.exit(0);
//        }
//    }
//}
