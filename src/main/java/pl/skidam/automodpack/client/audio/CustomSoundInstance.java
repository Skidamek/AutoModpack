package pl.skidam.automodpack.client.audio;

import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

import java.util.function.Supplier;

/*? if >=1.19.1 {*/
import net.minecraft.util.math.random.Random;
/*?}*/

public class CustomSoundInstance extends AbstractSoundInstance {

    public CustomSoundInstance(Supplier<SoundEvent> event) {
        /*? if >=1.21.2 {*/
        /*super(event.get().id(), SoundCategory.MASTER, Random.create());
        *//*?} elif >=1.19.1 {*/
        super(event.get().getId(), SoundCategory.MASTER, Random.create());
        /*?} else {*/
        /*super(event.get().getId(), SoundCategory.MASTER);
        *//*?}*/
        this.attenuationType = AttenuationType.NONE;
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
    public boolean isRepeatable() {
        return true;
    }
}