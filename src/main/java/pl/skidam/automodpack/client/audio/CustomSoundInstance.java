package pl.skidam.automodpack.client.audio;

import java.util.function.Supplier;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
/*? if >=1.19.1 {*/
import net.minecraft.util.RandomSource;
/*?}*/

public class CustomSoundInstance extends AbstractSoundInstance {

    public CustomSoundInstance(Supplier<SoundEvent> event) {
        /*? if >=1.21.2 {*/
        super(event.get().location(), SoundSource.MASTER, RandomSource.create());
        /*?} else if >=1.19.1 {*/
        /*super(event.get().getLocation(), SoundSource.MASTER, RandomSource.create());
        *//*?} else {*/
        /*super(event.get().getLocation(), SoundSource.MASTER);
        *//*?}*/
        this.attenuation = Attenuation.NONE;
    }

    @Override
    public float getVolume() {
        return 0.25f;
    }

    @Override
    public float getPitch() {
        return 1.0F;
    }

    @Override
    public boolean isLooping() {
        return true;
    }
}