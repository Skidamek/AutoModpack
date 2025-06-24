package pl.skidam.automodpack.client.ui.versioned;

/*? if >=1.20 {*/
import net.minecraft.client.gui.GuiGraphics;
/*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

public class VersionedMatrices  /*? if <1.20 {*/ /*extends PoseStack *//*?}*/   {

/*? if >=1.20 {*/
	private final GuiGraphics context;

	public VersionedMatrices(GuiGraphics context) {
		this.context = context;
	}

	public GuiGraphics getContext() {
		return context;
	}

	/*? if >=1.21.6 {*/
	public void pushPose() {
		context.pose().pushMatrix();
	}

	public void popPose() {
		context.pose().popMatrix();
	}

	public void scale(float x, float y, float z) {
		context.pose().scale(x, y);
	}
	/*?} else {*/
	/*public void pushPose() {
		context.pose().pushPose();
	}

	public void popPose() {
		context.pose().popPose();
	}

	public void scale(float x, float y, float z) {
		context.pose().scale(x, y, z);
	}
	*//*?}*/
/*?} else {*/
	/*public PoseStack getContext() {
		return this;
	}
*//*?}*/
}
