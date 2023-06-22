/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.client.audio;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
//#if MC >= 11903
//$$import net.minecraft.registry.Registries;
//#endif

//#if FORGE
//$$ import net.minecraftforge.eventbus.api.IEventBus;
//$$ import net.minecraftforge.registries.DeferredRegister;
//$$ import net.minecraftforge.registries.ForgeRegistries;
//#endif

import static pl.skidam.automodpack.GlobalVariables.MOD_ID;

@SuppressWarnings("deprecation")
public class AudioManager {
    private static CustomSoundInstance SOUND_INSTANCE;
    private static SoundManager soundManager;
    private static boolean playing = false;

    // FIXME: Forge support
    public AudioManager() {
//#if FABRICLIKE
        SoundEvent WAITING_MUSIC = register();
        SOUND_INSTANCE = new CustomSoundInstance(WAITING_MUSIC);
//#endif
    }


    private static SoundEvent register() {
        Identifier id = new Identifier(MOD_ID, "waiting_music");
//#if MC >= 11903
//$$    var register = Registries.SOUND_EVENT;
//$$    SoundEvent soundEvent = SoundEvent.of(id);
//#else
        Registry<SoundEvent> register = Registry.SOUND_EVENT;
        SoundEvent soundEvent = new SoundEvent(id);
//#endif
        return Registry.register(register, id, soundEvent);
    }

    public static void playMusic() {
        if (playing || SOUND_INSTANCE == null) return;

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
