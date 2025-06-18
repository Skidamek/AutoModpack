package pl.skidam.automodpack.client.ui.versioned;

/*? if >=1.20 {*/
import net.minecraft.client.gui.DrawContext;
/*?} else {*/
/*import net.minecraft.client.util.math.MatrixStack;
*//*?}*/

public class VersionedMatrices  /*? if <1.20 >>*/ /*extends MatrixStack*/   {

/*? if >=1.20 {*/
	private final DrawContext context;

	public VersionedMatrices(DrawContext context) {
		this.context = context;
	}

	public DrawContext getContext() {
		return context;
	}

	/*? if >=1.21.6 {*/
	/*public void push() {
		context.getMatrices().pushMatrix();
	}

	public void pop() {
		context.getMatrices().popMatrix();
	}

	public void scale(float x, float y, float z) {
		context.getMatrices().scale(x, y);
	}
	*//*?} else {*/
	public void push() {
		context.getMatrices().push();
	}

	public void pop() {
		context.getMatrices().pop();
	}

	public void scale(float x, float y, float z) {
		context.getMatrices().scale(x, y, z);
	}
	/*?}*/
/*?} else {*/
	/*public MatrixStack getContext() {
		return this;
	}
*//*?}*/
}
