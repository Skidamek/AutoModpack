package pl.skidam.automodpack.client.ui.versioned;

/*? if <1.20 {*/
import com.mojang.blaze3d.vertex.PoseStack;
/*?} else {*/
import net.minecraft.client.gui.GuiGraphics;
/*?}*/

public class VersionedMatrices {
    /*? if <1.20 {*/
    private PoseStack context;
    /*?} else {*/
    private GuiGraphics context;
    /*?}*/

    /*? if <1.20 {*/
    public VersionedMatrices() {
        this.context = new PoseStack();
    }

    public void set(PoseStack matrices) {
        this.context = matrices;
    }
    /*?} else {*/
    public VersionedMatrices(GuiGraphics matrices) {
        this.context = matrices;
    }
    /*?}*/

    /*? if <1.20 {*/
    public PoseStack getContext() {
        return context;
    }
    /*?} else {*/
    public GuiGraphics getContext() {
        return context;
    }
    /*?}*/

    public void pushPose() {
        /*? if <1.20 {*/
        context.pushPose();
        /*?} else {*/
        context.pose().pushPose();
        /*?}*/
    }

    public void popPose() {
        /*? if <1.20 {*/
        context.popPose();
        /*?} else {*/
        context.pose().popPose();
        /*?}*/
    }

    public void scale(float x, float y, float z) {
        /*? if <1.20 {*/
        context.scale(x, y, z);
        /*?} else {*/
        context.pose().scale(x, y, z);
        /*?}*/
    }
}