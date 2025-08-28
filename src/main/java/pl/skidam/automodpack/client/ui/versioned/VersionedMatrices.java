package pl.skidam.automodpack.client.ui.versioned;

/*? if >=1.20 {*/
import net.minecraft.client.gui.GuiGraphics;
/*?} else {*/
import com.mojang.blaze3d.vertex.PoseStack;
/*?}*/

public class VersionedMatrices {
    /*? if >=1.20 {*/
    private final GuiGraphics context;

    public VersionedMatrices(GuiGraphics context) {
        this.context = context;
    }

    public GuiGraphics getContext() {
        return context;
    }

    public void pushPose() {
        context.pose().pushPose();
    }

    public void popPose() {
        context.pose().popPose();
    }

    public void scale(float x, float y, float z) {
        context.pose().scale(x, y, z);
    }
    /*?} else {*/
    private PoseStack context;

    public VersionedMatrices() {
        this.context = new PoseStack();
    }

    public void set(PoseStack matrix) {
        this.context = matrix;
    }

    public PoseStack getContext() {
        return this.context;
    }

    public void pushPose() {
        context.pushPose();
    }

    public void popPose() {
        context.popPose();
    }

    public void scale(float x, float y, float z) {
        context.scale(x, y, z);
    }
    /*?}*/
}