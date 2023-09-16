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

package pl.skidam.automodpack.mixin.dev;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.loader.LoaderManager;
import pl.skidam.automodpack_core.screen.ScreenManager;

@Mixin(TitleScreen.class)
public class TestButton extends Screen {

    protected TestButton(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void init(CallbackInfo ci) {

        // check if we are in dev environment
        if (!new LoaderManager().isDevelopmentEnvironment()) {
            return;
        }

//#if MC >= 11700
        this.addDrawableChild(
//#else
//$$    this.addButton(
//#endif
                new TexturedButtonWidget(
                        this.width / 2 - 124,
                        90,
                        20,
                        20,
                        0,
                        106,
                        20,
                        ButtonWidget.WIDGETS_TEXTURE,
                        256,
                        256,
                        button -> {
                            AudioManager.playMusic();
                            new ScreenManager().menu();
                        },
                        VersionedText.translatable("gui.automodpack.menu")
                )
        );
    }
}
