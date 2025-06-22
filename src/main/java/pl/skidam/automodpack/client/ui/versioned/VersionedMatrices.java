package pl.skidam.automodpack.client.ui.versioned;

import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.vertex.PoseStack;

public class VersionedMatrices  /*? if <1.20 >>*/ /*extends MatrixStack*/   {

/*? if >=1.20 {*/
	private final GuiGraphics context;

	public VersionedMatrices(GuiGraphics context) {
		this.context = context;
	}

	public GuiGraphics getContext() {
		return context;
	}

	/*? if >=1.21.6 {*/
	public void push() {
		context.pose().pushMatrix();
	}

	public void pop() {
		context.pose().popMatrix();
	}

	public void scale(float x, float y, float z) {
		context.pose().scale(x, y);
	}
	/*?} else {*/
	/*public void push() {
		context.pose().pushPose();
	}

	public void pop() {
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
