package pl.skidam.automodpack.mixin;

import net.minecraft.client.sound.MusicTracker;
import net.minecraft.sound.MusicSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.audio.AudioManager;

@Mixin(MusicTracker.class)
public class MusicTrackerMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void play(MusicSound type, CallbackInfo ci) {
        if (AudioManager.isMusicPlaying()) {
            ci.cancel();
        }
    }
}
