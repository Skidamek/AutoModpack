package pl.skidam.automodpack.client.ui.versioned;

/*? if >=1.20 {*/
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumerProvider;
/*?} else {*/
/*import net.minecraft.client.util.math.MatrixStack;
*//*?}*/

public class VersionedMatrices extends /*? if >=1.20 >>*/  DrawContext /*? if <1.20 >>*/ /*MatrixStack*/   {

/*? if >=1.20 {*/
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
/*?}*/
}
