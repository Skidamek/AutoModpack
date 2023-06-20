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

package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.gui.Element; // 1.16
import net.minecraft.client.gui.widget.ClickableWidget; // 1.16
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

//#if MC < 12000
import net.minecraft.client.util.math.MatrixStack;
//#else
//$$import net.minecraft.client.gui.DrawContext;
//#endif

@SuppressWarnings("unchecked")
public class VersionedScreen extends Screen {

    protected VersionedScreen(Text title) {
        super(title);
    }

    @Override
//#if MC < 12000
    public void render(MatrixStack matrix, int mouseX, int mouseY, float delta) {
        VersionedMatrices matrices = new VersionedMatrices();
//#else
//$$public void render(DrawContext matrix, int mouseX, int mouseY, float delta) {
//$$    VersionedMatrices matrices = new VersionedMatrices(this.client, matrix.getVertexConsumers());
//#endif
        versionedRender(matrices, mouseX, mouseY, delta);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
    }

//#if MC < 11700
//$$    public <T extends Element> T addDrawableChild(T child) {
//$$        if (child instanceof ClickableWidget) {
//$$            return (T) super.addButton((ClickableWidget) child);
//$$        }
//$$        return super.addChild(child);
//$$   }
//#endif
}
