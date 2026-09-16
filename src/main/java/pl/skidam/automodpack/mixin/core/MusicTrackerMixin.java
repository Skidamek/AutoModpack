
package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import pl.skidam.automodpack.client.audio.AudioManager;

/*? if >=1.21.4 && <1.21.11 {*/
/*import net.minecraft.client.sounds.MusicInfo;
*//*?} else {*/
import net.minecraft.sounds.Music;
/*?}*/

@Mixin(MusicManager.class)
public class MusicTrackerMixin {

	@WrapMethod(method = "startPlaying")
	/*? if >=1.21.4 && <1.21.11 {*/
	/*private void play(MusicInfo music, Operation<Void> original) {
	*//*?} else {*/
	private void play(Music music, Operation<Void> original) {
	/*?}*/
		if (!AudioManager.isMusicPlaying()) original.call(music);
	}
}
