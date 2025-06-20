
package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.audio.AudioManager;

@Mixin(MusicManager.class)
public class MusicTrackerMixin {

    @Inject(
            method = "startPlaying",
            at = @At("HEAD"),
            cancellable = true
    )
    /*? if >1.21.4 {*/
    /*private void play(MusicInstance music, CallbackInfo ci) {
    *//*?} else {*/
    private void play(Music type, CallbackInfo ci) {
    /*?}*/
        if (AudioManager.isMusicPlaying()) {
            ci.cancel();
        }
    }
}