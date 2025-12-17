
package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.audio.AudioManager;

/*? if >=1.21.4 && <1.21.11 {*/
/*import net.minecraft.client.sounds.MusicInfo;
*//*?} else {*/
import net.minecraft.sounds.Music;
/*?}*/

@Mixin(MusicManager.class)
public class MusicTrackerMixin {

    @Inject(
            method = "startPlaying",
            at = @At("HEAD"),
            cancellable = true
    )
    /*? if >=1.21.4 && <1.21.11 {*/
    /*private void play(MusicInfo music, CallbackInfo ci) {
    *//*?} else {*/
    private void play(Music music, CallbackInfo ci) {
    /*?}*/
        if (AudioManager.isMusicPlaying()) {
            ci.cancel();
        }
    }
}