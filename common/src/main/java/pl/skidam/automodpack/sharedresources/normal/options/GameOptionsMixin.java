package pl.skidam.automodpack.sharedresources.normal.options;

import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

import static pl.skidam.automodpack.AutoModpack.selectedModpackDir;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 */

@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
    @Mutable @Shadow @Final private File optionsFile;

    @Inject(method = "load()V", at = @At(value = "HEAD"))
    private void sharedresources$overwriteOptionsPath(CallbackInfo ci) {
        if (selectedModpackDir == null) return;

        File newPath = new File(selectedModpackDir + File.separator + "options.txt");

//        AutoModpack.LOGGER.error("Overwriting options path to: {}, exist? {}", newPath.getAbsolutePath(), newPath.exists());
//        AutoModpack.LOGGER.error("Overwriting options path to: {}, exist? {}", newPath.getAbsolutePath(), newPath.exists());
//        AutoModpack.LOGGER.error("Overwriting options path to: {}, exist? {}", newPath.getAbsolutePath(), newPath.exists());
//
//        if (!newPath.exists()) {
//            File optionsFile = FabricLoader.getInstance().getGameDir().resolve("options.txt").toFile();
//            if (optionsFile.exists()) {
//                FileUtils.copyFile(optionsFile, newPath);
//            }
//        }

        if (newPath.exists()) {
            optionsFile = newPath;
        }
    }
}


