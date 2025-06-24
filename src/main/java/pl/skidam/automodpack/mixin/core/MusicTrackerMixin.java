
package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.audio.AudioManager;

/*? if >1.21.4 {*/
import net.minecraft.client.sounds.MusicInfo;
/*?} else {*/
/*import net.minecraft.sounds.Music;
*//*?}*/

@Mixin(MusicManager.class)
public class MusicTrackerMixin {

    @Inject(
            method = "startPlaying",
            at = @At("HEAD"),
            cancellable = true
    )
    /*? if >1.21.4 {*/
    private void play(MusicInfo p_383115_, CallbackInfo ci) {
    /*?} else {*/
    /*private void play(Music type, CallbackInfo ci) {
    *//*?}*/
        if (AudioManager.isMusicPlaying()) {
            ci.cancel();
        }
    }
}