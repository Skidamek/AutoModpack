package pl.skidam.automodpack.client.ui.versioned;

//#if MC >= 1200
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumerProvider;
//#else
//$$ import net.minecraft.client.util.math.MatrixStack;
//#endif

public class VersionedMatrices
//#if MC >= 1200
extends DrawContext
//#else
//$$ extends MatrixStack
//#endif
{

//#if MC >= 1200
public VersionedMatrices(MinecraftClient client, VertexConsumerProvider.Immediate vertexConsumers) {
   super(client, vertexConsumers);
}

public void push() {
   getMatrices().push();
}

public void pop() {
   getMatrices().pop();
}

public void scale(float x, float y, float z) {
   getMatrices().scale(x, y, z);
}
//#endif
}
