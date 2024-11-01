package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
/*? if >=1.20 {*/
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.client.render.VertexConsumerProvider;

// TODO find better way to do this, its mixin only for 1.20 and above
@Mixin(DrawContext.class)
/*?} else {*/
/*import pl.skidam.automodpack.init.Common;
@Mixin(Common.class)
*//*?}*/
public interface DrawContextAccessor {

    /*? if >=1.20 {*/
    @Accessor("vertexConsumers")
    VertexConsumerProvider.Immediate vertexConsumers();
    /*?}*/
}