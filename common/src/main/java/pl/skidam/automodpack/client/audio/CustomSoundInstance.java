package pl.skidam.automodpack.client.audio;


import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.random.Random;

public class CustomSoundInstance extends AbstractSoundInstance {

    CustomSoundInstance(SoundEvent event) {
        super(event.getId(), SoundCategory.MASTER, Random.create());
        this.attenuationType = SoundInstance.AttenuationType.NONE;
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