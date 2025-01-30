
package pl.skidam.automodpack.mixin.core;

/*? if >1.21.3 {*/
import net.minecraft.client.sound.MusicInstance;
/*?} else {*/
/*import net.minecraft.sound.MusicSound;
*//*?}*/
import net.minecraft.client.sound.MusicTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.audio.AudioManager;

@Mixin(MusicTracker.class)
public class MusicTrackerMixin {

    @Inject(
            method = "play",
            at = @At("HEAD"),
            cancellable = true
    )
    /*? if >1.21.3 {*/
    private void play(MusicInstance music, CallbackInfo ci) {
    /*?} else {*/
    /*private void play(MusicSound type, CallbackInfo ci) {
    *//*?}*/
        if (AudioManager.isMusicPlaying()) {
            ci.cancel();
        }
    }
}