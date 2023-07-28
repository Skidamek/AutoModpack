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

package pl.skidam.automodpack.mixin.core;

import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static pl.skidam.automodpack.GlobalVariables.*;

@Mixin(value = ServerLoginNetworkHandler.class, priority = 2137)
public class ServerLoginNetworkHandlerMixin {
    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    public void turnOffDisconnect(Text disconnectReason, CallbackInfo ci) {
        String reason = disconnectReason.toString().toLowerCase();
        if (reason.contains("automodpack")) return;

        if (keyWordsOfDisconnect.stream().anyMatch(reason::contains)) {
            ci.cancel();
        }
    }
}