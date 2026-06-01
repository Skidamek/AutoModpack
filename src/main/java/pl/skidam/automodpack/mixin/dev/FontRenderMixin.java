package pl.skidam.automodpack.mixin.dev;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.FormattedCharSequence;
/*? if >=26.1 {*/
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
/*?} else if >=1.19.3 {*/
/*import org.joml.Matrix4f;
*//*?} else {*/
/*import com.mojang.math.Matrix4f;
*//*?}*/
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.skidam.automodpack.client.autotest.FormattedText;
import pl.skidam.automodpack.client.autotest.RenderedTextCollector;

@Mixin(Font.class)
public abstract class FontRenderMixin {
    /*? if >=26.1 {*/
    @Inject(
            method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
            at = @At("HEAD"),
            require = 0)
    private void automodpack$drawString(String text, float x, float y, int color, boolean shadow, Matrix4fc pose, MultiBufferSource source, Font.DisplayMode mode, int background, int light, CallbackInfo ci) {
        RenderedTextCollector.record(text, x, y);
    }

    @Inject(
            method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
            at = @At("HEAD"),
            require = 0)
    private void automodpack$drawComponent(Component text, float x, float y, int color, boolean shadow, Matrix4fc pose, MultiBufferSource source, Font.DisplayMode mode, int background, int light, CallbackInfo ci) {
        RenderedTextCollector.record(FormattedText.toString(text.getVisualOrderText()), x, y);
    }

    @Inject(
            method = "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
            at = @At("HEAD"),
            require = 0)
    private void automodpack$drawSequence(FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4fc pose, MultiBufferSource source, Font.DisplayMode mode, int background, int light, CallbackInfo ci) {
        RenderedTextCollector.record(FormattedText.toString(text), x, y);
    }

    @Inject(method = "drawInBatch8xOutline", at = @At("HEAD"), require = 0)
    private void automodpack$drawOutline(FormattedCharSequence text, float x, float y, int color, int outlineColor, Matrix4fc pose, MultiBufferSource source, int light, CallbackInfo ci) {
        RenderedTextCollector.record(FormattedText.toString(text), x, y);
    }

    @Inject(
            method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At("HEAD"),
            require = 0)
    private void automodpack$prepareString(String text, float x, float y, int color, boolean shadow, int background, CallbackInfoReturnable<?> cir) {
        RenderedTextCollector.record(text, x, y);
    }

    @Inject(
            method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At("HEAD"),
            require = 0)
    private void automodpack$prepareSequence(FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean includeEmpty, int background, CallbackInfoReturnable<?> cir) {
        RenderedTextCollector.record(FormattedText.toString(text), x, y);
    }
    /*?} else if >=1.19.3 {*/
    /*@Inject(
            method = "renderText(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)F",
            at = @At("HEAD"),
            require = 0)
    private void automodpack$renderSequence(FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4f pose, MultiBufferSource source, Font.DisplayMode mode, int background, int light, CallbackInfoReturnable<Float> cir) {
        RenderedTextCollector.record(FormattedText.toString(text), x, y);
    }

    @Inject(
            method = "renderText(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)F",
            at = @At("HEAD"),
            require = 0)
    private void automodpack$renderString(String text, float x, float y, int color, boolean shadow, Matrix4f pose, MultiBufferSource source, Font.DisplayMode mode, int background, int light, CallbackInfoReturnable<Float> cir) {
        RenderedTextCollector.record(text, x, y);
    }

    @Inject(method = "drawInBatch8xOutline", at = @At("HEAD"), require = 0)
    private void automodpack$drawOutline(FormattedCharSequence text, float x, float y, int color, int outlineColor, Matrix4f pose, MultiBufferSource source, int light, CallbackInfo ci) {
        RenderedTextCollector.record(FormattedText.toString(text), x, y);
    }
    *//*?} else {*/
    /*@Inject(
            method = "renderText(Lnet/minecraft/util/FormattedCharSequence;FFIZLcom/mojang/math/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;ZII)F",
            at = @At("HEAD"),
            require = 0)
    private void automodpack$renderSequence(FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4f pose, MultiBufferSource source, boolean seeThrough, int background, int light, CallbackInfoReturnable<Float> cir) {
        RenderedTextCollector.record(FormattedText.toString(text), x, y);
    }

    @Inject(
            method = "renderText(Ljava/lang/String;FFIZLcom/mojang/math/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;ZII)F",
            at = @At("HEAD"),
            require = 0)
    private void automodpack$renderString(String text, float x, float y, int color, boolean shadow, Matrix4f pose, MultiBufferSource source, boolean seeThrough, int background, int light, CallbackInfoReturnable<Float> cir) {
        RenderedTextCollector.record(text, x, y);
    }
    *//*?}*/
}
