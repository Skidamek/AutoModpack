package pl.skidam.automodpack.client.sound;

import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.AutoModpackMain;
import net.minecraft.util.registry.Registry;

public class ModSounds {
    public static SoundEvent ELEVATOR_MUSIC = registerSoundEvent("elevator_music");
    private static SoundEvent registerSoundEvent(String name) {
        Identifier id = new Identifier(AutoModpackMain.MOD_ID, name);
        return Registry.register(Registry.SOUND_EVENT, id, new SoundEvent(id));
    }
}
