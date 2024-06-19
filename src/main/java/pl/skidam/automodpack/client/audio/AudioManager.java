package pl.skidam.automodpack.client.audio;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registry;
import java.util.function.Supplier;
import pl.skidam.automodpack.init.Common;

//#if MC >= 1193
import net.minecraft.registry.Registries;
//#endif

//#if NEOFORGE
//$$ import net.neoforged.bus.api.IEventBus;
//$$ import net.neoforged.neoforge.registries.DeferredRegister;
//$$ import net.neoforged.neoforge.registries.NeoForgeRegistries;
//$$ import static pl.skidam.automodpack_core.GlobalVariables.MOD_ID;
//#endif

//#if FORGE
//$$ import net.minecraftforge.eventbus.api.IEventBus;
//$$ import net.minecraftforge.registries.DeferredRegister;
//$$ import net.minecraftforge.registries.ForgeRegistries;
//$$ import net.minecraftforge.registries.RegistryObject;
//$$ import static pl.skidam.automodpack_core.GlobalVariables.MOD_ID;
//#endif

public class AudioManager {
    private static CustomSoundInstance SOUND_INSTANCE;
    private static SoundManager soundManager;
    private static boolean playing = false;

    private static final Identifier WAITING_MUSIC_ID = Common.id("waiting_music");

//#if MC >= 1193
   public static final SoundEvent WAITING_MUSIC_EVENT = SoundEvent.of(WAITING_MUSIC_ID);
//#else
//$$     private static final SoundEvent WAITING_MUSIC_EVENT = new SoundEvent(WAITING_MUSIC_ID);
//#endif

    private static Supplier<SoundEvent> WAITING_MUSIC;

//#if FORGE
//$$    public AudioManager(IEventBus eventBus) {
//$$        DeferredRegister<SoundEvent> SOUND_REGISTER = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID);
//$$        SOUND_REGISTER.register(eventBus);
//$$        WAITING_MUSIC = SOUND_REGISTER.register(WAITING_MUSIC_ID.getPath(),()-> WAITING_MUSIC_EVENT);
//$$    }
//#endif

//#if NEOFORGE
//$$     public AudioManager(IEventBus eventBus) {
//$$         DeferredRegister<SoundEvent> SOUND_REGISTER = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);
//$$         SOUND_REGISTER.register(eventBus);
//$$         WAITING_MUSIC = SOUND_REGISTER.register(WAITING_MUSIC_ID.getPath(),()-> WAITING_MUSIC_EVENT);
//$$     }
//#endif

//#if FABRIC
   public AudioManager() {
       SoundEvent waiting_music = register();
       WAITING_MUSIC = () -> waiting_music;
   }
//#endif

    private SoundEvent register() {
        Identifier id = Common.id("waiting_music");
//#if MC >= 1193
   Registry<SoundEvent> register = Registries.SOUND_EVENT;
//#else
//$$         Registry<SoundEvent> register = Registry.SOUND_EVENT;
//#endif

        return Registry.register(register, id, WAITING_MUSIC_EVENT);
    }

    public static void playMusic() {
        if (playing) return;
        if (SOUND_INSTANCE == null) {
            SOUND_INSTANCE = new CustomSoundInstance(WAITING_MUSIC);
        }

        getSoundManager().stopAll();
        getSoundManager().play(SOUND_INSTANCE);
        playing = true;
    }

    public static void stopMusic() {
        if (!playing || SOUND_INSTANCE == null) return;

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