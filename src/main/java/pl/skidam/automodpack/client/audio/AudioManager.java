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

//#if MC >= 11903
//$$ import net.minecraft.registry.Registries;
//#endif

import net.minecraft.util.registry.Registry;

//#if FORGE
//$$import net.minecraftforge.eventbus.api.IEventBus;
//$$import net.minecraftforge.registries.DeferredRegister;
//$$import net.minecraftforge.registries.ForgeRegistries;
//$$import net.minecraftforge.registries.RegistryObject;
//#endif

import java.util.function.Supplier;

import static pl.skidam.automodpack_common.GlobalVariables.MOD_ID;

public class AudioManager {
    private static CustomSoundInstance SOUND_INSTANCE;
    private static SoundManager soundManager;
    private static boolean playing = false;

    private static final Identifier WAITING_MUSIC_ID = new Identifier(MOD_ID, "waiting_music");

//#if MC >= 11903
//$$    public static final SoundEvent WAITING_MUSIC_EVENT = SoundEvent.of(WAITING_MUSIC_ID);
//#else
    private static final SoundEvent WAITING_MUSIC_EVENT = new SoundEvent(WAITING_MUSIC_ID);
//#endif

    private static Supplier<SoundEvent> WAITING_MUSIC;

//#if FORGE
//$$    public AudioManager(IEventBus eventBus) {
//$$
//$$        DeferredRegister<SoundEvent> SOUND_REGISTER = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID);
//$$        SOUND_REGISTER.register(eventBus);
//$$
//$$        WAITING_MUSIC = SOUND_REGISTER.register(WAITING_MUSIC_ID.getPath(),()-> WAITING_MUSIC_EVENT);
//$$    }
//#else

    public AudioManager() {
        SoundEvent waiting_music = register();
        WAITING_MUSIC = () -> waiting_music;
    }

    private SoundEvent register() {
        Identifier id = new Identifier(MOD_ID, "waiting_music");
//#if MC >= 11903
//$$    Registry<SoundEvent> register = Registries.SOUND_EVENT;
//#else
        Registry<SoundEvent> register = Registry.SOUND_EVENT;
//#endif

        return Registry.register(register, id, WAITING_MUSIC_EVENT);
    }
//#endif

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