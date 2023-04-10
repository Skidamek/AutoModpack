package pl.skidam.automodpack.client.audio;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

import static pl.skidam.automodpack.StaticVariables.MOD_ID;


public class AudioManager {
    private static CustomSoundInstance SOUND_INSTANCE;
    private static SoundManager soundManager;
    private static boolean playing = false;

    public AudioManager() {
        SoundEvent WAITING_MUSIC = register();
        SOUND_INSTANCE = new CustomSoundInstance(WAITING_MUSIC);
    }

    private static SoundEvent register() {
        Identifier id = new Identifier(MOD_ID, "waiting_music");
        return Registry.register(Registry.SOUND_EVENT, id, new SoundEvent(id));
    }

    public static void playMusic() {
        if (playing) return;

        getSoundManager().stopAll();
        getSoundManager().play(SOUND_INSTANCE);
        playing = true;
    }

    public static void stopMusic() {
        if (!playing) return;

        getSoundManager().stopAll();
        playing = false;
    }

    private static SoundManager getSoundManager() {
        if(soundManager == null) {
            soundManager = MinecraftClient.getInstance().getSoundManager();
        }
        return soundManager;
    }

    public static boolean isMusicPlaying() {
        return playing;
    }
}
