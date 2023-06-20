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


import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
//#if MC >= 11902
//$$import net.minecraft.util.math.random.Random;
//#endif
public class CustomSoundInstance extends AbstractSoundInstance {

    CustomSoundInstance(SoundEvent event) {
//#if MC >= 11902
//$$    super(event.getId(), SoundCategory.MASTER, Random.create());
//#else
        super(event.getId(), SoundCategory.MASTER);
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