package pl.skidam.automodpack.client.audio;

import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

import java.util.function.Supplier;

//#if MC >= 1192
import net.minecraft.util.math.random.Random;
//#endif
public class CustomSoundInstance extends AbstractSoundInstance {

    public CustomSoundInstance(Supplier<SoundEvent> event) {
//#if MC >= 1192
   super(event.get().getId(), SoundCategory.MASTER, Random.create());
//#else
//$$         super(event.get().getId(), SoundCategory.MASTER);
//#endif
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